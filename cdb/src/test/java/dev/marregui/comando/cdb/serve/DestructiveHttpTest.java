package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.store.DestructiveStressTest;
import dev.marregui.comando.cdb.store.DestructiveStressTest.Model;
import dev.marregui.comando.cdb.store.DestructiveStressTest.Refused;
import dev.marregui.comando.cdb.store.DestructiveStressTest.Target;

import static org.assertj.core.api.Assertions.assertThat;

/// The destructive schedules of `DestructiveStressTest` over HTTP: a `CdbServer` on an ephemeral loopback port, no
/// auth, line protocol posted to `/write`, changes and reads through `/exec`, so the server's request threads and its
/// commit path race as a client sees them. Seeds as `DestructiveStressTest`'s, printed on every run.
class DestructiveHttpTest {

    /// The serial schedule of 240 changes over HTTP: every read a whole snapshot of a state in its window, every
    /// change answering the model's count, and the model's rows at the end.
    @Test
    void readersSeeWholeSnapshotsOverHttp(@TempDir final Path root) throws Exception {
        final SplittableRandom random = DestructiveStressTest.random("readersSeeWholeSnapshotsOverHttp");
        try (Cdb cdb = Cdb.open(root);
                CdbServer server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                Http http = new Http()) {
            final Target target = new HttpTarget(http, server.port());
            final Model end = DestructiveStressTest.serial(target, random, DestructiveStressTest.SERIAL_OPS);
            if (end.present()) {
                assertThat(target.rows()).isEqualTo(end.rows());
            } else {
                assertThat(cdb.tables()).doesNotContain(DestructiveStressTest.TABLE);
            }
        }
    }

    /// The contended schedule over HTTP: two writers posting line protocol and one sending deletes and partition
    /// drops that spare the protected batches; no request fails and no protected row goes missing.
    @Test
    void contendedChangesLoseNoProtectedRowOverHttp(@TempDir final Path root) throws Exception {
        final SplittableRandom random = DestructiveStressTest.random("contendedChangesLoseNoProtectedRowOverHttp");
        try (Cdb cdb = Cdb.open(root);
                CdbServer server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                Http http = new Http()) {
            DestructiveStressTest.contended(new HttpTarget(http, server.port()), random);
        }
    }

    /// The dropping schedule over HTTP: `TRUNCATE`, `DROP TABLE` and `DROP TABLE IF EXISTS` race two writers posting
    /// line protocol and four readers; every read a whole suffix of the commits, a write after a drop recreating the
    /// table, no write refused, a read or destroy refused only where a drop can have left the table absent.
    @Test
    void truncatesAndDropsRaceWritersAndReadersOverHttp(@TempDir final Path root) throws Exception {
        final SplittableRandom random = DestructiveStressTest.random("truncatesAndDropsRaceWritersAndReadersOverHttp");
        try (Cdb cdb = Cdb.open(root);
                CdbServer server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                Http http = new Http()) {
            DestructiveStressTest.dropping(new HttpTarget(http, server.port()), random);
        }
    }

    /// The target over HTTP: an answer other than 200 (204 for `/write`) is the refusal, its body the message.
    private record HttpTarget(Http http, int port) implements Target {

        @Override
        public void append(final long batch, final long cls, final int day)
                throws IOException, InterruptedException, Refused {
            final StringBuilder lines = new StringBuilder();
            for (int n = 0; n < DestructiveStressTest.ROWS; n++) {
                lines.append(DestructiveStressTest.TABLE).append(" b=").append(batch).append("i,c=").append(cls)
                        .append("i,n=").append(n).append("i ").append(DestructiveStressTest.micros(batch, day, n))
                        .append('\n');
            }
            final HttpResponse<String> answer = http.post(port, "/write?precision=u", lines.toString());
            if (answer.statusCode() != 204) {
                throw new Refused("write answered " + answer.statusCode() + ": " + answer.body(), null);
            }
        }

        @Override
        public long execute(final String sql) throws IOException, InterruptedException, Refused {
            final Map<String, Object> answer = exec(sql);
            if (answer.containsKey("ddl")) {
                return 0L;
            }
            return (Long) answer.get("updated");
        }

        @Override
        public Map<Long, Long> counts() throws IOException, InterruptedException, Refused {
            final Map<Long, Long> counts = new HashMap<>();
            for (final List<?> row : dataset("SELECT b, count() FROM " + DestructiveStressTest.TABLE)) {
                counts.put((Long) row.get(0), (Long) row.get(1));
            }
            return counts;
        }

        @Override
        public Set<Long> rows() throws IOException, InterruptedException, Refused {
            final Set<Long> rows = new HashSet<>();
            for (final List<?> row : dataset("SELECT b, n FROM " + DestructiveStressTest.TABLE)) {
                rows.add((Long) row.get(0) * 16L + (Long) row.get(1));
            }
            return rows;
        }

        private List<List<?>> dataset(final String sql) throws IOException, InterruptedException, Refused {
            final List<List<?>> rows = new ArrayList<>();
            for (final Object row : (List<?>) exec(sql).get("dataset")) {
                rows.add((List<?>) row);
            }
            return rows;
        }

        private Map<String, Object> exec(final String sql) throws IOException, InterruptedException, Refused {
            final HttpResponse<String> answer = http.exec(port, sql, "");
            if (answer.statusCode() != 200) {
                throw new Refused("exec answered " + answer.statusCode() + ": " + answer.body(), null);
            }
            @SuppressWarnings("unchecked") // the server answers /exec with one JSON object
            final Map<String, Object> json = (Map<String, Object>) new Json(answer.body()).value();
            if (json.containsKey("error")) {
                throw new Refused("exec answered an error: " + answer.body(), null);
            }
            return json;
        }
    }

    /// A reader of the JSON the server writes: objects as maps, arrays as lists, integers as `Long`, other numbers as
    /// `Double`, strings unescaped, `true`, `false`, `null`.
    private static final class Json {

        private final String text;
        private int at;

        Json(final String text) {
            this.text = text;
        }

        Object value() {
            space();
            final char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            final Map<String, Object> object = new LinkedHashMap<>();
            at++;
            space();
            if (text.charAt(at) == '}') {
                at++;
                return object;
            }
            while (true) {
                space();
                final String key = string();
                space();
                expect(':');
                object.put(key, value());
                space();
                if (text.charAt(at++) == '}') {
                    return object;
                }
            }
        }

        private List<Object> array() {
            final List<Object> array = new ArrayList<>();
            at++;
            space();
            if (text.charAt(at) == ']') {
                at++;
                return array;
            }
            while (true) {
                array.add(value());
                space();
                if (text.charAt(at++) == ']') {
                    return array;
                }
            }
        }

        private String string() {
            expect('"');
            final StringBuilder s = new StringBuilder();
            while (true) {
                final char c = text.charAt(at++);
                if (c == '"') {
                    return s.toString();
                }
                if (c != '\\') {
                    s.append(c);
                    continue;
                }
                final char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> s.append('\n');
                    case 't' -> s.append('\t');
                    case 'r' -> s.append('\r');
                    case 'b' -> s.append('\b');
                    case 'f' -> s.append('\f');
                    case 'u' -> {
                        s.append((char) Integer.parseInt(text, at, at + 4, 16));
                        at += 4;
                    }
                    default -> s.append(e);
                }
            }
        }

        private Object number() {
            final int from = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            final String number = text.substring(from, at);
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                return Long.parseLong(number);
            }
            return Double.parseDouble(number);
        }

        private Object word(final String word, final Object meaning) {
            if (!text.startsWith(word, at)) {
                throw new IllegalArgumentException("cannot read JSON [at=" + at + ", expected=" + word + ']');
            }
            at += word.length();
            return meaning;
        }

        private void expect(final char c) {
            if (text.charAt(at++) != c) {
                throw new IllegalArgumentException("cannot read JSON [at=" + (at - 1) + ", expected=" + c + ']');
            }
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
