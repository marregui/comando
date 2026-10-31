package dev.marregui.comando.core.schema;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Schemas by connection name, written by the database adapter, read by the editor's completion. */
public final class SchemaRegistry {

    private final ConcurrentHashMap<String, Schema> schemas = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public Schema of(final String connection) {
        return schemas.getOrDefault(connection, Schema.EMPTY);
    }

    public void put(final String connection, final Schema schema) {
        schemas.put(connection, schema);
        for (final Consumer<String> l : listeners) {
            l.accept(connection);
        }
    }

    public void forget(final String connection) {
        schemas.remove(connection);
    }

    /** Called with the connection name after its schema changed; on the writer's thread. */
    public void addListener(final Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(final Consumer<String> listener) {
        listeners.remove(listener);
    }
}
