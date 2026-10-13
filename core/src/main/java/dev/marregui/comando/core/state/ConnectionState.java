package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.Status;

import java.util.Objects;

/** A configured connection and its live status. */
public record ConnectionState(ConnectionSpec spec, Status status, String error) {

    public ConnectionState {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
    }

    public static ConnectionState closed(ConnectionSpec spec) {
        return new ConnectionState(spec, Status.CLOSED, "");
    }

    public String name() {
        return spec.name();
    }

    public ConnectionState with(Status newStatus, String newError) {
        return new ConnectionState(spec, newStatus, newError);
    }
}
