package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Configuration of one database connection over the Postgres wire protocol.
 *
 * @param name     unique name, the key panels bind to
 * @param host     server host
 * @param port     server port
 * @param database database name
 * @param user     login user
 * @param password login password; the store keeps it in the OS secret store where there is one
 * @param prod     a production connection: writes from Claude are refused and the UI frames it in red
 * @param sslMode  the driver's TLS mode, one of {@link #SSL_MODES}; null reads as {@code prefer}
 */
public record ConnectionSpec(String name, String host, int port, String database, String user, String password, boolean prod,
                             String sslMode) {

    public static final int DEFAULT_PORT = 8812;

    /** The fields with the password written {@code ***}: a spec printed in a log or an error never carries it. */
    @Override
    public String toString() {
        return "ConnectionSpec[name=" + name + ", host=" + host + ", port=" + port + ", database=" + database + ", user="
                + user + ", password=***, prod=" + prod + ", sslMode=" + sslMode + ']';
    }
    /**
     * TLS modes: {@code prefer} tries TLS and falls back; {@code require} encrypts; {@code verify-full} also checks the
     * certificate and host.
     */
    public static final List<String> SSL_MODES = List.of("prefer", "disable", "allow", "require", "verify-ca", "verify-full");

    public ConnectionSpec(String name, String host, int port, String database, String user, String password, boolean prod) {
        this(name, host, port, database, user, password, prod, "prefer");
    }

    public ConnectionSpec {
        if (sslMode == null || sslMode.isBlank()) {
            sslMode = "prefer";
        }
        if (!SSL_MODES.contains(sslMode)) {
            throw new IllegalArgumentException("sslMode is one of " + SSL_MODES + " [sslMode=" + sslMode + ']');
        }
        requireName(name);
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host is empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range [port=" + port + ']');
        }
    }

    /** The QuestDB defaults: localhost:8812, database qdb, admin/quest. */
    public static ConnectionSpec questDbDefaults(String name) {
        return new ConnectionSpec(name, "localhost", DEFAULT_PORT, "qdb", "admin", "quest", false);
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ':' + port + '/' + database;
    }

    public String label() {
        return user + '@' + host + ':' + port + '/' + database;
    }

    public ConnectionSpec withName(String newName) {
        return new ConnectionSpec(newName, host, port, database, user, password, prod, sslMode);
    }

    public ConnectionSpec withPassword(String newPassword) {
        return new ConnectionSpec(name, host, port, database, user, newPassword, prod, sslMode);
    }

    static void requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is empty");
        }
    }
}
