package dev.marregui.comando.app.lab;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.frontear.watch.Published;

import static org.assertj.core.api.Assertions.assertThat;

/// The lab sink's JSON check against cdb's, over payloads that are rare on the wire: lone surrogates escaped and
/// encoded, a NUL, bytes that are not UTF-8 and overlong forms. A payload reaches each check the way it does in
/// comando: decoded by [Published#text], judged by [PacketSink#json], and, when that says JSON, handed to cdb as the
/// same string, which cdb encodes to UTF-8 and scans before it keeps the row. The sink never takes for JSON what cdb
/// then refuses, since cdb refuses the whole line and the packet is lost. Each check is also run on the raw bytes, to
/// show where the two parsers differ before decoding. cdb is on this module's test classpath only at run time, so its
/// scanner is reached by name.
class PayloadJsonCheckTest {

    // cdb's scanner and the answer it gives for a valid text
    private static final String CDB_JSON = "dev.marregui.comando.cdb.json.Json";
    private static final int CDB_VALID = -1;
    private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

    /// Every input of the corpus gets the verdicts it has: where the two checks part, the sink sends the payload to
    /// text and cdb would have kept it as JSON, never the other way; on the raw bytes, comando's reader refuses what is
    /// not UTF-8 (RFC 8259 §8.1) and cdb's scanner, which takes its bytes as well-formed UTF-8, does not look.
    @Test
    void theSinkNeverTakesForJsonAPayloadCdbRefuses() throws Throwable {
        final MethodHandle cdb = cdbInvalidAt();
        final List<Verdicts> expected = List.of(
                new Verdicts("valid object", true, true, true, true),
                new Verdicts("lone high surrogate escaped in a value", true, true, true, true),
                new Verdicts("lone high surrogate escaped in a name", false, true, false, true),
                new Verdicts("lone low surrogate escaped in a value", true, true, true, true),
                new Verdicts("lone low surrogate escaped in a name", false, true, false, true),
                new Verdicts("surrogate pair escaped", true, true, true, true),
                new Verdicts("surrogate pair escaped in a name", true, true, true, true),
                new Verdicts("supplementary character as UTF-8", true, true, true, true),
                new Verdicts("lone high surrogate as CESU-8 bytes", true, true, false, true),
                new Verdicts("lone low surrogate as CESU-8 bytes", true, true, false, true),
                new Verdicts("surrogate pair as CESU-8 bytes", true, true, false, true),
                new Verdicts("U+0000 escaped", true, true, true, true),
                new Verdicts("U+0000 as a raw byte", false, false, false, false),
                new Verdicts("byte 0xFF", true, true, false, true),
                new Verdicts("UTF-8 lead byte with no continuation", true, true, false, true),
                new Verdicts("overlong solidus C0 AF", true, true, false, true),
                new Verdicts("overlong NUL C0 80", true, true, false, true),
                new Verdicts("overlong solidus E0 80 AF", true, true, false, true),
                new Verdicts("broken grammar", false, false, false, false));

        final List<Verdicts> seen = new ArrayList<>();
        final StringBuilder table = new StringBuilder("| input | bytes | sink | cdb | comando raw | cdb raw |\n");
        for (final Input in : corpus()) {
            final String text = decoded(in.bytes());
            final boolean sink = PacketSink.json(packet(in.bytes().length, text));
            final boolean byCdb = validToCdb(cdb, text.getBytes(StandardCharsets.UTF_8));
            final Verdicts v = new Verdicts(in.name(), sink, byCdb, validToComando(in.bytes()),
                    validToCdb(cdb, in.bytes()));
            seen.add(v);
            table.append("| ").append(in.name()).append(" | `").append(HEX.formatHex(in.bytes())).append("` | ")
                    .append(v.sink()).append(" | ").append(v.cdb()).append(" | ").append(v.comandoRaw()).append(" | ")
                    .append(v.cdbRaw()).append(" |\n");
        }
        System.out.print(table);

        assertThat(seen).as("the verdicts of each input:\n%s", table).containsExactlyElementsOf(expected);
        assertThat(seen).as("a payload the sink takes for JSON and cdb refuses").noneMatch(v -> v.sink() && !v.cdb());
    }

    /// A string holding a lone surrogate character, which decoding bytes never yields, is JSON to the sink and to
    /// cdb, which encodes the character as `?`, as `String.getBytes` and the writer's body do.
    @Test
    void aLoneSurrogateCharacterInTheStringIsJsonToBoth() throws Throwable {
        final String text = "{\"v\":\"" + (char) 0xD800 + "\"}";
        final byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        assertThat(PacketSink.json(packet(utf8.length, text))).isTrue();
        assertThat(new String(utf8, StandardCharsets.UTF_8)).isEqualTo("{\"v\":\"?\"}");
        assertThat(validToCdb(cdbInvalidAt(), utf8)).isTrue();
    }

    private static List<Input> corpus() {
        return List.of(
                Input.ascii("valid object", "{\"a\":1,\"b\":[true,null,\"x\"]}"),
                Input.ascii("lone high surrogate escaped in a value", "{\"v\":\"" + escape("D800") + "\"}"),
                Input.ascii("lone high surrogate escaped in a name", "{\"" + escape("D800") + "\":1}"),
                Input.ascii("lone low surrogate escaped in a value", "{\"v\":\"" + escape("DC00") + "\"}"),
                Input.ascii("lone low surrogate escaped in a name", "{\"" + escape("DC00") + "\":1}"),
                Input.ascii("surrogate pair escaped", "{\"v\":\"" + escape("D83D") + escape("DE00") + "\"}"),
                Input.ascii("surrogate pair escaped in a name", "{\"" + escape("D83D") + escape("DE00") + "\":1}"),
                Input.inString("supplementary character as UTF-8", "F0 9F 98 80"),
                Input.inString("lone high surrogate as CESU-8 bytes", "ED A0 80"),
                Input.inString("lone low surrogate as CESU-8 bytes", "ED B0 80"),
                Input.inString("surrogate pair as CESU-8 bytes", "ED A0 BD ED B8 80"),
                Input.ascii("U+0000 escaped", "{\"v\":\"" + escape("0000") + "\"}"),
                Input.inString("U+0000 as a raw byte", "00"),
                Input.inString("byte 0xFF", "FF"),
                Input.inString("UTF-8 lead byte with no continuation", "C3"),
                Input.inString("overlong solidus C0 AF", "C0 AF"),
                Input.inString("overlong NUL C0 80", "C0 80"),
                Input.inString("overlong solidus E0 80 AF", "E0 80 AF"),
                Input.ascii("broken grammar", "{\"a\":}"));
    }

    /// A JSON escape of the UTF-16 unit `hex`, built in parts so no escape of the compiler's is written here.
    private static String escape(final String hex) {
        return "\\" + 'u' + hex;
    }

    /// The payload as the lab's drain decodes it.
    private static String decoded(final byte[] payload) {
        return new Published(1L, 1L, "pub", "t", 1, false, payload.length, 1, null, payload, 0).text();
    }

    private static Packet packet(final int bytes, final String payload) {
        return new Packet(1L, 1L, Kind.PUBLISHED, "pub", "", "t", 1, false, bytes, 0, -1L, "", "", "", payload);
    }

    private static boolean validToComando(final byte[] bytes) {
        try {
            Json.readTree(bytes);
            return true;
        } catch (final JsonException _) {
            return false;
        }
    }

    private static boolean validToCdb(final MethodHandle invalidAt, final byte[] bytes) throws Throwable {
        return (int) invalidAt.invokeExact(bytes, 0, bytes.length) == CDB_VALID;
    }

    /// cdb's `Json.invalidAt` bound to a new scanner, the one cdb's writer runs on a JSON column's value.
    private static MethodHandle cdbInvalidAt() throws Throwable {
        final Class<?> type = Class.forName(CDB_JSON);
        final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        final Object scanner = lookup.findConstructor(type, MethodType.methodType(void.class)).invoke();
        return lookup.findVirtual(type, "invalidAt", MethodType.methodType(int.class, byte[].class, int.class,
                int.class)).bindTo(scanner);
    }

    /// One input of the corpus: its name and the payload's bytes.
    private record Input(String name, byte[] bytes) {

        static Input ascii(final String name, final String text) {
            return new Input(name, text.getBytes(StandardCharsets.US_ASCII));
        }

        /// `{"v":"a<bytes>b"}` with the bytes `hex` spells in the string.
        static Input inString(final String name, final String hex) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("{\"v\":\"a".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(HEX.parseHex(hex));
            out.writeBytes("b\"}".getBytes(StandardCharsets.US_ASCII));
            return new Input(name, out.toByteArray());
        }
    }

    /// What each check said of one input: the sink and cdb on the path a payload takes, and each parser on its bytes.
    private record Verdicts(String name, boolean sink, boolean cdb, boolean comandoRaw, boolean cdbRaw) {
    }
}
