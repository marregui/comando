package dev.marregui.comando.ui.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * comando's own settings, kept with its other state in the data root as {@code settings.json} (record 106): one JSON
 * object of keys, each a string, a number or a boolean, in key order. Read once when the window opens; written whole,
 * atomically, by {@link #flush} after a change. Without a file, the values kept before in the user's Java preferences
 * are read once and written to it; the preferences stay as they were. A window without a data root (some tests) keeps
 * its settings in memory. EDT only.
 */
final class Settings {

    private static final System.Logger LOG = System.getLogger(Settings.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern INT = Pattern.compile("-?\\d{1,9}");

    private final Path file;
    private final TreeMap<String, Object> values;
    private boolean dirty;

    private Settings(Path file, TreeMap<String, Object> values) {
        this.file = file;
        this.values = values;
    }

    /** Settings that live as long as the window: nothing is read or written. */
    static Settings inMemory() {
        return new Settings(null, new TreeMap<>());
    }

    /**
     * The settings in {@code file}; when it does not exist yet, those kept in {@code legacy}, which is left as it was,
     * written to it. An unreadable file is kept aside as {@code settings.json.unreadable} and the settings start empty.
     */
    static Settings open(Path file, Preferences legacy) {
        if (Files.exists(file)) {
            try {
                return new Settings(file, read(JSON.readTree(file.toFile())));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read " + file + ", kept aside: " + e.getMessage());
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moved) {
                    LOG.log(System.Logger.Level.WARNING, "cannot keep " + file + " aside: " + moved.getMessage());
                }
                return new Settings(file, new TreeMap<>());
            }
        }
        Settings s = new Settings(file, fromPreferences(legacy));
        s.dirty = !s.values.isEmpty();
        s.flush();
        return s;
    }

    private static TreeMap<String, Object> read(JsonNode root) {
        TreeMap<String, Object> out = new TreeMap<>();
        if (root != null && root.isObject()) {
            root.properties().forEach(e -> {
                JsonNode v = e.getValue();
                if (v.isBoolean()) {
                    out.put(e.getKey(), v.booleanValue());
                } else if (v.isIntegralNumber() && v.canConvertToInt()) {
                    out.put(e.getKey(), v.intValue());
                } else if (v.isValueNode()) {
                    out.put(e.getKey(), v.asText());
                }
            });
        }
        return out;
    }

    /** The preferences' values, each typed as it reads: an integer, true or false, else text. */
    static TreeMap<String, Object> fromPreferences(Preferences prefs) {
        TreeMap<String, Object> out = new TreeMap<>();
        try {
            for (String key : prefs.keys()) {
                String v = prefs.get(key, null);
                if (v != null) {
                    out.put(key, INT.matcher(v).matches() ? Integer.valueOf(v)
                            : v.equals("true") || v.equals("false") ? Boolean.valueOf(v) : v);
                }
            }
        } catch (BackingStoreException | IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the earlier preferences: " + e.getMessage());
        }
        return out;
    }

    String get(String key, String otherwise) {
        Object v = values.get(key);
        return v == null ? otherwise : v.toString();
    }

    int getInt(String key, int otherwise) {
        return values.get(key) instanceof Integer i ? i : otherwise;
    }

    boolean getBoolean(String key, boolean otherwise) {
        return values.get(key) instanceof Boolean b ? b : otherwise;
    }

    void put(String key, String value) {
        set(key, value);
    }

    void putInt(String key, int value) {
        set(key, value);
    }

    void putBoolean(String key, boolean value) {
        set(key, value);
    }

    void remove(String key) {
        dirty |= values.remove(key) != null;
    }

    private void set(String key, Object value) {
        dirty |= !value.equals(values.put(key, value));
    }

    /** The keys and values as kept; for tests. */
    Map<String, Object> values() {
        return Map.copyOf(values);
    }

    /** Writes the settings when they changed since the last write: to a file beside it, then moved over it. */
    void flush() {
        if (!dirty || file == null) {
            dirty = false;
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        values.forEach((k, v) -> {
            switch (v) {
                case Integer i -> root.put(k, i);
                case Boolean b -> root.put(k, b);
                default -> root.put(k, v.toString());
            }
        });
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            JSON.writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write " + file + ": " + e.getMessage());
        }
    }
}
