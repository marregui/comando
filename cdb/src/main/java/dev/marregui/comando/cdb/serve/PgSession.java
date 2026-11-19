package dev.marregui.comando.cdb.serve;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.exec.InsertRunner;

/**
 * One PostgreSQL wire (protocol 3.0) connection to a cdb, as QuestDB's port 8812 serves one: any user name, the
 * database name ignored, SSL and GSS encryption refused with {@code N}, a cancel request handed to the server's
 * {@link Cancels} and then closed. Without {@link Secrets} any client is the owner (AuthenticationOk at once); with
 * them the password is asked in clear text and must be one of them: a wrong one is refused with {@code 28P01} and
 * the connection closed, and a session opened with the read-only secret refuses every
 * statement but a query, SHOW or EXPLAIN with {@code 42501}. A query past the {@link Policy}'s deadline is
 * cancelled ({@code 57014}; a change takes no cancel and runs to its end) and a result past its rows refused
 * ({@code 54000}); statement text never reaches the log. Simple queries run each {@code
 * ;}-separated statement; the extended protocol keeps named and unnamed statements and portals, binds {@code $n}
 * parameters as SQL literals ({@link PgTypes#literal}), and after an error skips to the next Sync; any other message
 * that fails, an unknown one included, is answered with an error and ReadyForQuery. Every count and length read from
 * the wire is checked against what the message holds before anything is sized by it. Session statements are answered
 * here ({@link PgSql#local}); everything else is cdb's SQL. Transactions are the driver's view only: cdb applies each
 * statement when it runs, and the ReadyForQuery status ({@code I}, {@code T}, {@code E}) follows BEGIN, COMMIT,
 * ROLLBACK and errors so a driver with autocommit off works. Runs on its connection's thread, which it owns alone.
 */
final class PgSession {

    /** The {@code server_version} QuestDB 10.2 reports in its ParameterStatus. */
    static final String SERVER_VERSION = "11.3";
    static final String SERVER_VERSION_NUM = "110003";
    /** A query and its parameters are a few kB from Cosa or Grafana; the same bound as an HTTP {@code /write} body. */
    static final int MAX_MESSAGE_BYTES = CdbServer.MAX_BODY_BYTES;
    /**
     * pgjdbc keeps at most 256 server-side statements a connection by default ({@code preparedStatementCacheQueries});
     * four times that bounds a connection's statements and, separately, its portals.
     */
    static final int MAX_PREPARED = 1024;
    private static final System.Logger LOG = System.getLogger(PgSession.class.getName());
    private static final int PROTOCOL_3 = 196_608;
    private static final int SSL_REQUEST = 80_877_103;
    private static final int GSSENC_REQUEST = 80_877_104;
    private static final int CANCEL_REQUEST = 80_877_102;
    /** A startup packet holds a few parameters; PostgreSQL refuses one over 10,000 bytes. */
    private static final int MAX_STARTUP_BYTES = 10_000;
    /** PostgreSQL's {@code AuthenticationCleartextPassword} request code. */
    private static final int AUTH_CLEARTEXT = 3;
    private static final String SYNTAX_ERROR = "42000";
    /** PostgreSQL's {@code query_canceled}: a statement stopped by a cancel request or its deadline. */
    private static final String QUERY_CANCELED = "57014";
    /** PostgreSQL's {@code invalid_password}. */
    private static final String INVALID_PASSWORD = "28P01";
    /** PostgreSQL's {@code insufficient_privilege}: a change asked with the read-only secret. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** PostgreSQL's {@code program_limit_exceeded}: a result over the row limit. */
    private static final String PROGRAM_LIMIT_EXCEEDED = "54000";
    private static final String PROTOCOL_VIOLATION = "08P01";
    private static final String INTERNAL_ERROR = "XX000";
    private static final short[] TEXT = {};

    private final Cancels cancels;
    private final Cdb cdb;
    private final Deadlines deadlines;
    private final DataInputStream in;
    private final PgOutput out;
    private final Policy policy;
    private final Map<String, Portal> portals = new HashMap<>();
    private final int processId;
    private final int secretKey;
    private final Map<String, Prepared> statements = new HashMap<>();
    // what the client's password lets it do, set by the startup and read by every statement, on the session's thread
    private Role role = Role.NONE;
    // the session's read lag, set and read on the session's thread; null runs queries with the store's default
    private Long maxLagMillis;
    // whether the session's writes wait to be durable (SET cdb.durable), set and read on the session's thread
    private boolean durable;
    // the cancel of the statement this session runs or ran last: written by the session's thread before each run,
    // read by a cancel request's thread; a cancel that arrives after its statement ended reaches a spent cancel
    private volatile CdbCancel running;
    private boolean skipping;
    private char transaction = 'I';

    PgSession(
            final Cdb cdb,
            final DataInputStream in,
            final PgOutput out,
            final int processId,
            final int secretKey,
            final Policy policy,
            final Cancels cancels,
            final Deadlines deadlines) {
        this.cdb = cdb;
        this.in = in;
        this.out = out;
        this.processId = processId;
        this.secretKey = secretKey;
        this.policy = policy;
        this.cancels = cancels;
        this.deadlines = deadlines;
    }

    /** Cancels the statement this session runs when {@code key} is its secret key; from any thread. */
    void cancel(final int key) {
        final CdbCancel statement = running;
        if (key == secretKey && statement != null) {
            statement.cancel();
        }
    }

    /** Serves the started connection until the client terminates it or it closes; returns then. */
    void run() throws IOException {
        while (true) {
            final int type = in.read();
            if (type < 0) {
                return;
            }
            final int length = in.readInt();
            if (length < 4 || length - 4 > MAX_MESSAGE_BYTES) {
                fatal(PROTOCOL_VIOLATION, "refuse message length [type=" + (char) type + ", length=" + length
                        + ", limit=" + MAX_MESSAGE_BYTES + ']');
                return;
            }
            final ByteBuffer body = ByteBuffer.wrap(read(length - 4));
            if (type == 'X') {
                return;
            }
            if (skipping && type != 'S') {
                continue;
            }
            try {
                message((char) type, body);
            } catch (final Refused e) {
                failed((char) type, e.code, e.getMessage(), -1);
            } catch (final CdbException e) {
                failed((char) type, SYNTAX_ERROR, e.getMessage(), e.position());
            } catch (final BufferUnderflowException _) {
                failed((char) type, PROTOCOL_VIOLATION, "malformed message [type=" + (char) type + ", length="
                        + length + ']', -1);
            } catch (final RuntimeException e) {
                LOG.log(Level.WARNING, "could not answer message [type=" + (char) type + ']', e);
                failed((char) type, INTERNAL_ERROR, "could not answer message [error=" + e + ']', -1);
            }
        }
    }

    /**
     * Reads the startup packet and greets the client: true when the session goes on to {@link #run()}, false when
     * it ends here (a cancel request, a refused packet).
     */
    boolean startup() throws IOException {
        while (true) {
            final int length = in.readInt();
            if (length < 8 || length > MAX_STARTUP_BYTES) {
                fatal(PROTOCOL_VIOLATION, "refuse startup packet [length=" + length + ", limit=" + MAX_STARTUP_BYTES
                        + ']');
                return false;
            }
            final int code = in.readInt();
            final ByteBuffer body = ByteBuffer.wrap(read(length - 8));
            switch (code) {
                case SSL_REQUEST, GSSENC_REQUEST -> {
                    out.int8('N').flush();
                }
                case CANCEL_REQUEST -> {
                    if (body.remaining() >= 2 * Integer.BYTES) {
                        cancels.cancel(body.getInt(), body.getInt());
                    }
                    return false;
                }
                case PROTOCOL_3 -> {
                    final Map<String, String> parameters = parameters(body);
                    if (!authenticate(parameters.get("user"))) {
                        return false;
                    }
                    greet(parameters);
                    return true;
                }
                default -> {
                    fatal(PROTOCOL_VIOLATION, "unsupported protocol [version=" + (code >>> 16) + '.'
                            + (code & 0xFFFF) + ']');
                    return false;
                }
            }
        }
    }

    // without secrets every client is the owner; with them the client's password names its role, and a password that
    // is neither secret ends the session before it starts. The password itself is never logged nor answered back.
    private boolean authenticate(final String user) throws IOException {
        final Secrets secrets = policy.secrets();
        if (secrets == null) {
            role = Role.OWNER;
            return true;
        }
        out.begin('R').int32(AUTH_CLEARTEXT).end();
        out.flush();
        final int type = in.read();
        if (type < 0) {
            throw new EOFException("connection closed before its password");
        }
        final int length = in.readInt();
        if (type != 'p' || length < 5 || length > MAX_STARTUP_BYTES) {
            fatal(PROTOCOL_VIOLATION, "refuse password message [type=" + (char) type + ", length=" + length
                    + ", limit=" + MAX_STARTUP_BYTES + ']');
            return false;
        }
        final String password;
        try {
            password = cstring(ByteBuffer.wrap(read(length - 4)));
        } catch (final CdbException e) {
            fatal(PROTOCOL_VIOLATION, e.getMessage());
            return false;
        }
        role = secrets.roleOf(password);
        if (role == Role.NONE) {
            fatal(INVALID_PASSWORD, "password authentication failed [user=" + user + ']');
            return false;
        }
        return true;
    }

    private void greet(final Map<String, String> parameters) throws IOException {
        out.begin('R').int32(0).end();
        status("application_name", parameters.getOrDefault("application_name", ""));
        status("client_encoding", "UTF8");
        status("DateStyle", "ISO, MDY");
        status("integer_datetimes", "on");
        status("is_superuser", "off");
        status("server_encoding", "UTF8");
        status("server_version", SERVER_VERSION);
        status("session_authorization", parameters.getOrDefault("user", ""));
        status("standard_conforming_strings", "on");
        status("TimeZone", "UTC");
        out.begin('K').int32(processId).int32(secretKey).end();
        ready();
        LOG.log(Level.DEBUG, () -> "opened pg session [process=" + processId + ", user=" + parameters.get("user")
                + ", application=" + parameters.get("application_name") + ']');
    }

    private void message(final char type, final ByteBuffer body) throws IOException {
        switch (type) {
            case 'Q' -> query(cstring(body));
            case 'P' -> parse(body);
            case 'B' -> bind(body);
            case 'D' -> describe(body);
            case 'E' -> execute(body);
            case 'C' -> close(body);
            case 'S' -> {
                skipping = false;
                ready();
            }
            case 'H' -> out.flush();
            case 'p' -> {
                // a password after the startup asked for none, or a second one: the role stays the startup's
            }
            default -> throw new CdbException("unsupported message [type=" + type + ']', -1);
        }
    }

    private void query(final String sql) throws IOException {
        final List<String> parts = PgSql.split(sql);
        if (parts.isEmpty()) {
            out.begin('I').end();
        }
        for (final String part : parts) {
            try {
                simple(part);
            } catch (final Refused e) {
                error(e.code, e.getMessage(), -1);
                break;
            } catch (final CdbException e) {
                error(SYNTAX_ERROR, e.getMessage(), e.position());
                break;
            } catch (final RuntimeException e) {
                // the statement's text stays out of the log: it may carry data or a secret
                LOG.log(Level.WARNING, "could not run statement [process=" + processId + ", statement="
                        + PgSql.keyword(part) + ']', e);
                error(INTERNAL_ERROR, "could not run statement [error=" + e + ']', -1);
                break;
            }
        }
        ready();
    }

    private void simple(final String sql) throws IOException {
        final PgSql.Local local = PgSql.local(sql);
        if (local != null) {
            if (local.hasRow()) {
                localDescription(local, TEXT);
            }
            answer(local);
            return;
        }
        allow(sql);
        final CdbResult result = runQuery(sql);
        if (isQuery(result)) {
            description(result, TEXT);
            rows(result, 0, result.rowCount(), TEXT);
            complete("SELECT " + result.rowCount());
        } else {
            complete(tag(sql, result));
        }
    }

    // a durable session binds its setting for the statement, which runs on this thread, for its INSERT to read
    private CdbResult runQuery(final String sql) {
        return durable ? ScopedValue.where(InsertRunner.DURABLE, Boolean.TRUE).call(() -> run(sql)) : run(sql);
    }

    // a session opened with the read-only secret runs queries, SHOW and EXPLAIN only
    private void allow(final String sql) {
        if (role != Role.OWNER && !Access.readOnly(sql)) {
            throw new Refused(INSUFFICIENT_PRIVILEGE, "refuse a change with the read-only secret [statement="
                    + PgSql.keyword(sql) + ']', null);
        }
    }

    // the statement runs under its deadline, which cancels it as a cancel request would; a result with more rows than
    // the limit is refused rather than cut short, so a client never takes a partial answer for the whole one
    private CdbResult run(final String sql) {
        final CdbCancel cancel = new CdbCancel();
        running = cancel;
        final long startNanos = System.nanoTime();
        final Runnable ended = deadlines.start(cancel);
        final CdbResult result;
        try {
            result = maxLagMillis == null ? cdb.run(sql, 0L, policy.maxRows(), cancel)
                    : cdb.run(sql, policy.maxRows(), maxLagMillis, cancel);
        } catch (final CdbException e) {
            if (cancel.cancelled()) {
                final long ranMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                throw ranMillis >= policy.deadlineMillis()
                        ? new Refused(QUERY_CANCELED, "cancel statement past its deadline [deadlineMillis="
                                + policy.deadlineMillis() + ", ranMillis=" + ranMillis + ']', e)
                        : new Refused(QUERY_CANCELED, e.getMessage(), e);
            }
            throw e;
        } finally {
            ended.run();
        }
        if (result.truncated()) {
            throw new Refused(PROGRAM_LIMIT_EXCEEDED, "refuse result over the row limit [limit=" + policy.maxRows()
                    + ']', null);
        }
        return result;
    }

    private void parse(final ByteBuffer body) throws IOException {
        final String name = cstring(body);
        final String sql = cstring(body);
        final int declared = body.getShort() & 0xFFFF;
        need(body, (long) declared * Integer.BYTES);
        final int[] oids = new int[Math.max(declared, PgSql.parameterCount(sql))];
        for (int i = 0; i < declared; i++) {
            oids[i] = body.getInt();
        }
        admit(statements, name, "statement");
        statements.put(name, new Prepared(sql, oids, PgSql.local(sql)));
        out.begin('1').end();
    }

    private void bind(final ByteBuffer body) throws IOException {
        final String portal = cstring(body);
        final String name = cstring(body);
        final Prepared statement = statements.get(name);
        if (statement == null) {
            throw new CdbException("unknown prepared statement [name=" + name + ']', -1);
        }
        final short[] formats = shorts(body);
        final int count = body.getShort() & 0xFFFF;
        need(body, (long) count * Integer.BYTES);
        final String[] literals = new String[count];
        for (int i = 0; i < count; i++) {
            final int length = body.getInt();
            if (length < -1) {
                throw new BufferUnderflowException();
            }
            need(body, length);
            final byte[] value = length < 0 ? null : new byte[length];
            if (value != null) {
                body.get(value);
            }
            final int oid = i < statement.oids().length ? statement.oids()[i] : PgTypes.UNSPECIFIED;
            literals[i] = PgTypes.literal(oid, format(formats, i), value);
        }
        final short[] results = shorts(body);
        final String sql = statement.local() == null ? PgSql.bind(statement.sql(), literals) : statement.sql();
        admit(portals, portal, "portal");
        portals.put(portal, new Portal(statement, sql, results));
        out.begin('2').end();
    }

    private void describe(final ByteBuffer body) throws IOException {
        final char kind = (char) body.get();
        final String name = cstring(body);
        if (kind == 'S') {
            final Prepared statement = statements.get(name);
            if (statement == null) {
                throw new CdbException("unknown prepared statement [name=" + name + ']', -1);
            }
            out.begin('t').int16(statement.oids().length);
            for (final int oid : statement.oids()) {
                out.int32(oid);
            }
            out.end();
            if (statement.local() != null) {
                localDescription(statement.local(), TEXT);
            } else if (PgSql.changesStore(statement.sql())) {
                out.begin('n').end();
            } else {
                description(describe(statement), TEXT);
            }
            return;
        }
        final Portal portal = portals.get(name);
        if (portal == null) {
            throw new CdbException("unknown portal [name=" + name + ']', -1);
        }
        if (portal.statement.local() != null) {
            localDescription(portal.statement.local(), portal.formats);
        } else {
            description(portal.result != null ? portal.result : cdb.describe(portal.sql), portal.formats);
        }
    }

    // the statement planned with a typed placeholder per parameter; an unspecified one is null, then 0 when cdb
    // refuses null where it stands (a LIMIT)
    private CdbResult describe(final Prepared statement) {
        final int[] oids = statement.oids();
        final String[] typed = new String[oids.length];
        for (int i = 0; i < oids.length; i++) {
            typed[i] = PgTypes.placeholder(oids[i]);
        }
        try {
            return cdb.describe(PgSql.bind(statement.sql(), typed));
        } catch (final CdbException first) {
            boolean other = false;
            for (int i = 0; i < oids.length; i++) {
                if (oids[i] == PgTypes.UNSPECIFIED) {
                    typed[i] = "0";
                    other = true;
                }
            }
            if (!other) {
                throw first;
            }
            try {
                return cdb.describe(PgSql.bind(statement.sql(), typed));
            } catch (final CdbException _) {
                throw first;
            }
        }
    }

    private void execute(final ByteBuffer body) throws IOException {
        final String name = cstring(body);
        final int maxRows = body.getInt();
        final Portal portal = portals.get(name);
        if (portal == null) {
            throw new CdbException("unknown portal [name=" + name + ']', -1);
        }
        if (portal.statement.local() != null) {
            answer(portal.statement.local());
            return;
        }
        if (portal.result == null) {
            allow(portal.sql);
            portal.result = runQuery(portal.sql);
        }
        final CdbResult result = portal.result;
        if (!isQuery(result)) {
            complete(tag(portal.sql, result));
            return;
        }
        final int end = maxRows > 0 ? (int) Math.min(result.rowCount(), (long) portal.next + maxRows)
                : result.rowCount();
        rows(result, portal.next, end, portal.formats);
        final int sent = end - portal.next;
        portal.next = end;
        if (end < result.rowCount()) {
            out.begin('s').end();
        } else {
            complete("SELECT " + sent);
        }
    }

    private void close(final ByteBuffer body) throws IOException {
        final char kind = (char) body.get();
        final String name = cstring(body);
        if (kind == 'S') {
            statements.remove(name);
        } else {
            portals.remove(name);
        }
        out.begin('3').end();
    }

    // a session row's one column is VARCHAR, whose binary form is its text, so the format asked changes nothing
    private void answer(final PgSql.Local local) throws IOException {
        switch (local.tag()) {
            case "BEGIN" -> transaction = 'T';
            case "COMMIT", "ROLLBACK" -> transaction = 'I';
            default -> {
                // the transaction status stays
            }
        }
        if (local.lag() != null) {
            maxLagMillis = local.lag().millis();
        }
        if (local.durable() != null) {
            durable = local.durable().on();
        }
        if ("SET".equals(local.tag()) && local.value() != null) {
            status("application_name", local.value());
        }
        if (local.hasRow()) {
            out.begin('D').int16(1);
            final byte[] value = local.value().getBytes(StandardCharsets.UTF_8);
            out.int32(value.length).bytes(value).end();
        }
        complete(local.tag());
    }

    private void description(final CdbResult result, final short[] formats) throws IOException {
        if (result.columnCount() == 0) {
            out.begin('n').end();
            return;
        }
        out.begin('T').int16(result.columnCount());
        for (int column = 0; column < result.columnCount(); column++) {
            out.cstring(result.columnName(column)).int32(0).int16(0).int32(PgTypes.oid(result.type(column)))
                    .int16(PgTypes.size(result.type(column))).int32(-1).int16(format(formats, column));
        }
        out.end();
    }

    private void localDescription(final PgSql.Local local, final short[] formats) throws IOException {
        if (!local.hasRow()) {
            out.begin('n').end();
            return;
        }
        out.begin('T').int16(1).cstring(local.column()).int32(0).int16(0).int32(PgTypes.VARCHAR).int16(-1).int32(-1)
                .int16(format(formats, 0)).end();
    }

    private void rows(final CdbResult result, final int from, final int to, final short[] formats) throws IOException {
        final int columns = result.columnCount();
        for (int row = from; row < to; row++) {
            out.begin('D').int16(columns);
            for (int column = 0; column < columns; column++) {
                PgTypes.value(out, result, row, column, format(formats, column));
            }
            out.end();
        }
    }

    private void complete(final String tag) throws IOException {
        out.begin('C').cstring(tag).end();
    }

    private void ready() throws IOException {
        out.begin('Z').int8(transaction).end();
        out.flush();
    }

    private void status(final String name, final String value) throws IOException {
        out.begin('S').cstring(name).cstring(value).end();
    }

    // an error in the extended protocol: answered, then every message up to the next Sync is passed over; any other
    // message, an unknown one included, has no Sync to come, so it is answered and the session made ready again
    private void failed(final char type, final String code, final String message, final int position)
            throws IOException {
        error(code, message, position);
        switch (type) {
            case 'P', 'B', 'D', 'E', 'C', 'H' -> skipping = true;
            default -> ready();
        }
    }

    private void error(final String code, final String message, final int position) throws IOException {
        if (transaction != 'I') {
            transaction = 'E';
        }
        out.begin('E').int8('S').cstring("ERROR").int8('V').cstring("ERROR").int8('C').cstring(code).int8('M')
                .cstring(message == null ? "" : message);
        if (position >= 0) {
            out.int8('P').cstring(Integer.toString(position + 1));
        }
        out.int8(0).end();
    }

    private void fatal(final String code, final String message) throws IOException {
        out.begin('E').int8('S').cstring("FATAL").int8('V').cstring("FATAL").int8('C').cstring(code)
                .int8('M').cstring(message).int8(0).end();
        out.flush();
        LOG.log(Level.DEBUG, () -> "closed pg session [process=" + processId + ", reason=" + message + ']');
    }

    private byte[] read(final int length) throws IOException {
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException("connection closed inside a message [length=" + length + ']');
        }
        return bytes;
    }

    private static <V> void admit(final Map<String, V> map, final String name, final String what) {
        if (!name.isEmpty() && !map.containsKey(name) && map.size() >= MAX_PREPARED) {
            throw new CdbException("refuse " + what + " over the limit [name=" + name + ", limit=" + MAX_PREPARED
                    + ']', -1);
        }
    }

    private static boolean isQuery(final CdbResult result) {
        return result.columnCount() > 0 || result.updateCount() < 0;
    }

    private static String tag(final String sql, final CdbResult result) {
        final long count = Math.max(0, result.updateCount());
        final String keyword = PgSql.keyword(sql);
        return switch (keyword) {
            case "INSERT" -> "INSERT 0 " + count;
            case "UPDATE", "DELETE" -> keyword + ' ' + count;
            case "CREATE", "ALTER", "DROP", "TRUNCATE" -> keyword + " TABLE";
            default -> keyword;
        };
    }

    // PostgreSQL's format codes: none for all text, one for every column or parameter, else one each
    private static short format(final short[] formats, final int index) {
        if (formats.length == 0) {
            return 0;
        }
        return formats.length == 1 ? formats[0] : index < formats.length ? formats[index] : 0;
    }

    // a message shorter than the `bytes` it claims to hold next is malformed, refused before anything is sized by it
    private static void need(final ByteBuffer body, final long bytes) {
        if (bytes > body.remaining()) {
            throw new BufferUnderflowException();
        }
    }

    private static short[] shorts(final ByteBuffer body) {
        final int count = body.getShort() & 0xFFFF;
        need(body, (long) count * Short.BYTES);
        final short[] values = new short[count];
        for (int i = 0; i < values.length; i++) {
            values[i] = body.getShort();
        }
        return values;
    }

    private static Map<String, String> parameters(final ByteBuffer body) {
        final Map<String, String> parameters = new HashMap<>();
        while (body.hasRemaining()) {
            final String key = cstring(body);
            if (key.isEmpty()) {
                break;
            }
            parameters.put(key, cstring(body));
        }
        return parameters;
    }

    private static String cstring(final ByteBuffer body) {
        final int start = body.position();
        int end = start;
        while (end < body.limit() && body.get(end) != 0) {
            end++;
        }
        if (end == body.limit()) {
            throw new CdbException("unterminated string in message [offset=" + start + ']', -1);
        }
        body.position(end + 1);
        return new String(body.array(), start, end - start, StandardCharsets.UTF_8);
    }

    /** Where a cancel request goes: the server, which knows every started session by its process id. */
    @FunctionalInterface
    interface Cancels {

        /** Cancels the statement of the session {@code processId} when {@code secretKey} is its key. */
        void cancel(int processId, int secretKey);
    }

    /** Where a statement's deadline is kept: the server, which owns the threads that time it. */
    @FunctionalInterface
    interface Deadlines {

        /** Cancels {@code cancel} once the deadline passes, unless the returned action, the statement's end, ran. */
        Runnable start(CdbCancel cancel);
    }

    /**
     * What the server sets for each of its sessions: the secrets a password must match ({@code null}: no password
     * asked, every client the owner), how long a statement may run, and how many rows a result may hold.
     */
    record Policy(Secrets secrets, long deadlineMillis, int maxRows) {
    }

    /** A parsed statement: its SQL with {@code $n} placeholders, a type per parameter (0 unspecified). */
    private record Prepared(String sql, int[] oids, PgSql.Local local) {
    }

    /**
     * A statement refused or stopped with a SQLSTATE of its own: a cancel request or the deadline, the read-only
     * secret, the row limit.
     */
    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        Refused(final String code, final String message, final CdbException cause) {
            super(message, cause, false, false);
            this.code = code;
        }
    }

    /** A bound statement: the SQL cdb runs, the result formats asked, and, once run, its rows and the next to send. */
    private static final class Portal {

        private final short[] formats;
        private final String sql;
        private final Prepared statement;
        private int next;
        private CdbResult result;

        Portal(final Prepared statement, final String sql, final short[] formats) {
            this.statement = statement;
            this.sql = sql;
            this.formats = formats;
        }
    }
}
