package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.ConnectionSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connections as a resource kind. */
public final class ConnectionKind implements ResourceKind<ConnectionSpec> {

    public static final ConnectionKind INSTANCE = new ConnectionKind();

    private static final List<Attribute> ATTRIBUTES = List.of(
            Attribute.of("name", "Name", Attribute.Kind.TEXT, true, "unique; panels bind to it"),
            Attribute.of("host", "Host", Attribute.Kind.TEXT, true, ""),
            Attribute.of("port", "Port", Attribute.Kind.INTEGER, true, "8812 for QuestDB, 5432 for Postgres"),
            Attribute.of("database", "Database", Attribute.Kind.TEXT, true, ""),
            Attribute.of("user", "User", Attribute.Kind.TEXT, true, ""),
            Attribute.of("password", "Password", Attribute.Kind.SECRET, false, ""),
            Attribute.of("prod", "Production", Attribute.Kind.BOOLEAN, false, "Claude may not write; shown in red"),
            Attribute.choice("sslMode", "TLS", ConnectionSpec.SSL_MODES,
                    "prefer falls back to plain; require encrypts; verify-full also checks the certificate (~/.postgresql/root.crt)"));

    private ConnectionKind() {
    }

    @Override
    public String noun() {
        return "connection";
    }

    @Override
    public List<Attribute> attributes() {
        return ATTRIBUTES;
    }

    @Override
    public Map<String, String> toAttributes(ConnectionSpec s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("host", s.host());
        m.put("port", Integer.toString(s.port()));
        m.put("database", s.database());
        m.put("user", s.user());
        m.put("password", s.password());
        m.put("prod", Boolean.toString(s.prod()));
        m.put("sslMode", s.sslMode());
        return m;
    }

    @Override
    public ConnectionSpec fromAttributes(Map<String, String> a) {
        int port;
        try {
            port = Integer.parseInt(a.getOrDefault("port", "").strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port is not a number: " + a.get("port"));
        }
        return new ConnectionSpec(
                a.getOrDefault("name", "").strip(),
                a.getOrDefault("host", "").strip(),
                port,
                a.getOrDefault("database", "").strip(),
                a.getOrDefault("user", "").strip(),
                a.getOrDefault("password", ""),
                Boolean.parseBoolean(a.getOrDefault("prod", "false")),
                a.getOrDefault("sslMode", "prefer"));
    }

    @Override
    public String nameOf(ConnectionSpec spec) {
        return spec.name();
    }

    @Override
    public ConnectionSpec withName(ConnectionSpec spec, String name) {
        return spec.withName(name);
    }

    @Override
    public ConnectionSpec template(String name) {
        return ConnectionSpec.questDbDefaults(name);
    }
}
