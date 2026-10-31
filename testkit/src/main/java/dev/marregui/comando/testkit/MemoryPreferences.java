package dev.marregui.comando.testkit;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/** Java preferences kept in memory, so a test never reads or writes the user's store. */
public final class MemoryPreferences extends AbstractPreferences {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, MemoryPreferences> children = new HashMap<>();

    public MemoryPreferences() {
        this(null, "");
    }

    private MemoryPreferences(final MemoryPreferences parent, final String name) {
        super(parent, name);
    }

    @Override
    protected void putSpi(final String key, final String value) {
        values.put(key, value);
    }

    @Override
    protected String getSpi(final String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(final String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
        values.clear();
        children.clear();
    }

    @Override
    protected String[] keysSpi() {
        return values.keySet().toArray(String[]::new);
    }

    @Override
    protected String[] childrenNamesSpi() {
        return children.keySet().toArray(String[]::new);
    }

    @Override
    protected AbstractPreferences childSpi(final String name) {
        return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n));
    }

    @Override
    protected void syncSpi() {
    }

    @Override
    protected void flushSpi() {
    }
}
