package dev.marregui.comando.core.schema;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Schemas by connection name, written by the database adapter, read by the editor's completion. */
public final class SchemaRegistry {

    private final ConcurrentHashMap<String, Schema> schemas = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public Schema of(String connection) {
        return schemas.getOrDefault(connection, Schema.EMPTY);
    }

    public void put(String connection, Schema schema) {
        schemas.put(connection, schema);
        for (Consumer<String> l : listeners) {
            l.accept(connection);
        }
    }

    public void forget(String connection) {
        schemas.remove(connection);
    }

    /** Called with the connection name after its schema changed; on the writer's thread. */
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }
}
