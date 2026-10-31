package dev.marregui.comando.core.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;

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
                    "prefer falls back to plain; verify-full checks ~/.postgresql/root.crt"));

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
    public Map<String, String> toAttributes(final ConnectionSpec s) {
        final Map<String, String> m = new LinkedHashMap<>();
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
    public ConnectionSpec fromAttributes(final Map<String, String> a) {
        int port;
        try {
            port = Integer.parseInt(a.getOrDefault("port", "").strip());
        } catch (final NumberFormatException e) {
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
    public String nameOf(final ConnectionSpec spec) {
        return spec.name();
    }

    @Override
    public ConnectionSpec withName(final ConnectionSpec spec, final String name) {
        return spec.withName(name);
    }

    @Override
    public ConnectionSpec template(final String name) {
        return ConnectionSpec.questDbDefaults(name);
    }

    @Override
    public String noticeSubject(final String name) {
        return Notice.connection(name);
    }

    @Override
    public String removalQuestion(final AppState s, final String name) {
        final List<String> parts = new ArrayList<>();
        final ConnectionState c = s.connection(name).orElse(null);
        if (c != null && c.status().isUsable()) {
            parts.add("closes it");
        }
        int panels = 0;
        // closed panels keep their bindings, so removal unbinds them too
        for (final PanelState p : s.panels()) {
            if (p.connection().equals(name)) {
                panels++;
            }
        }
        for (final PanelState p : s.closed()) {
            if (p.connection().equals(name)) {
                panels++;
            }
        }
        if (panels > 0) {
            parts.add("unbinds " + ResourceKind.count(panels, "panel"));
        }
        if (c != null && !c.spec().password().isEmpty()) {
            parts.add("deletes its saved password");
        }
        final String question = "Remove connection " + name + "?";
        return parts.isEmpty() ? question : question + " This " + ResourceKind.sentence(parts) + ".";
    }
}
