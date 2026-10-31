package dev.marregui.comando.core.state;

import java.util.Objects;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.Status;

/** A configured connection and its live status. */
public record ConnectionState(ConnectionSpec spec, Status status, String error) {

    public ConnectionState {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
    }

    public static ConnectionState closed(final ConnectionSpec spec) {
        return new ConnectionState(spec, Status.CLOSED, "");
    }

    public String name() {
        return spec.name();
    }

    public ConnectionState with(final Status newStatus, final String newError) {
        return new ConnectionState(spec, newStatus, newError);
    }
}
