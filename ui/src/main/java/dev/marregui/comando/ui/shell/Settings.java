package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.model.AtomicFiles;

/**
 * comando's own settings, kept with its other state in the data root as {@code settings.json} (record 106): one JSON
 * object of keys, each a string, a number or a boolean, in key order, after its schema's {@code version} (record 133;
 * a file without it is version 1). Read once when the window opens; written whole, atomically, by {@link #flush}
 * after a change. A file of a later version is kept as it is and never written: the window runs on the defaults and
 * the log says why. Without a file, the values kept before in the user's Java preferences are read once and written to
 * it; the preferences stay as they were. A window without a data root (some tests) keeps its settings in memory. EDT
 * only.
 */
final class Settings {

    private static final System.Logger LOG = System.getLogger(Settings.class.getName());
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final Pattern INT = Pattern.compile("-?\\d{1,9}");
    /** The schema's version this comando reads and writes, and the key that names it. */
    static final int VERSION = 1;
    static final String VERSION_KEY = "version";

    private final Path file;
    private final TreeMap<String, Object> values;
    private boolean dirty;
    /** A file of a later version: kept as it is, never written. */
    private boolean refused;
    /** Why the file is not read, in the words the user is told once (record 133); empty when it read. */
    private String refusal = "";

    private Settings(final Path file, final TreeMap<String, Object> values) {
        this.file = file;
        this.values = values;
    }

    /** Why the file was not read, for the user, once (record 133); empty when it read. */
    String refusal() {
        return refusal;
    }

    /** Settings that live as long as the window: nothing is read or written. */
    static Settings inMemory() {
        return new Settings(null, new TreeMap<>());
    }

    /**
     * The settings in {@code file}; when it does not exist yet, those kept in {@code legacy}, which is left as it was,
     * written to it. An unreadable file is kept aside as {@code settings.json.unreadable} and the settings start empty.
     */
    static Settings open(final Path file, final Preferences legacy) {
        if (Files.exists(file)) {
            try {
                final JsonNode root = JSON.readTree(file.toFile());
                final int version = root != null && root.isObject() ? root.path(VERSION_KEY).asInt(1) : 1;
                if (version > VERSION) {
                    final Settings later = new Settings(file, new TreeMap<>());
                    later.refused = true;
                    later.refusal = file.getFileName() + " is of version " + version + ", written by a later comando; this one reads version "
                            + VERSION + ". It is kept as it is and nothing is saved to it until a comando that reads it runs; the window"
                            + " runs on its defaults";
                    LOG.log(System.Logger.Level.WARNING, later.refusal + " [file=" + file + ']');
                    return later;
                }
                return new Settings(file, read(root));
            } catch (final JacksonException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read the settings, kept aside [file=" + file + ']', e);
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
                } catch (final IOException moved) {
                    LOG.log(System.Logger.Level.WARNING, "cannot keep the settings aside [file=" + file + ']', moved);
                }
                return new Settings(file, new TreeMap<>());
            }
        }
        final Settings s = new Settings(file, fromPreferences(legacy));
        s.dirty = !s.values.isEmpty();
        s.flush();
        return s;
    }

    private static TreeMap<String, Object> read(final JsonNode root) {
        final TreeMap<String, Object> out = new TreeMap<>();
        if (root != null && root.isObject()) {
            root.properties().forEach(e -> {
                if (e.getKey().equals(VERSION_KEY)) {
                    return;
                }
                final JsonNode v = e.getValue();
                if (v.isBoolean()) {
                    out.put(e.getKey(), v.booleanValue());
                } else if (v.isIntegralNumber() && v.canConvertToInt()) {
                    out.put(e.getKey(), v.intValue());
                } else if (v.isValueNode()) {
                    out.put(e.getKey(), v.asString(""));
                }
            });
        }
        return out;
    }

    /** The preferences' values, each typed as it reads: an integer, true or false, else text. */
    static TreeMap<String, Object> fromPreferences(final Preferences prefs) {
        final TreeMap<String, Object> out = new TreeMap<>();
        try {
            for (final String key : prefs.keys()) {
                final String v = prefs.get(key, null);
                if (v != null) {
                    out.put(key, INT.matcher(v).matches() ? Integer.valueOf(v)
                            : v.equals("true") || v.equals("false") ? Boolean.valueOf(v) : v);
                }
            }
        } catch (final BackingStoreException | IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the earlier preferences", e);
        }
        return out;
    }

    String get(final String key, final String otherwise) {
        final Object v = values.get(key);
        return v == null ? otherwise : v.toString();
    }

    int getInt(final String key, final int otherwise) {
        return values.get(key) instanceof Integer i ? i : otherwise;
    }

    boolean getBoolean(final String key, final boolean otherwise) {
        return values.get(key) instanceof Boolean b ? b : otherwise;
    }

    void put(final String key, final String value) {
        set(key, value);
    }

    void putInt(final String key, final int value) {
        set(key, value);
    }

    void putBoolean(final String key, final boolean value) {
        set(key, value);
    }

    void remove(final String key) {
        dirty |= values.remove(key) != null;
    }

    private void set(final String key, final Object value) {
        if (key.equals(VERSION_KEY)) {
            throw new IllegalArgumentException(VERSION_KEY + " names the file's schema, not a setting");
        }
        dirty |= !value.equals(values.put(key, value));
    }

    /** The keys and values as kept, a copy: for a family of keys under one prefix, and for tests. */
    Map<String, Object> values() {
        return Map.copyOf(values);
    }

    /** Writes the settings when they changed since the last write: to a file beside it, then moved over it. */
    void flush() {
        if (!dirty || file == null || refused) {
            dirty = false;
            return;
        }
        final ObjectNode root = JSON.createObjectNode();
        root.put(VERSION_KEY, VERSION);
        values.forEach((k, v) -> {
            switch (v) {
                case Integer i -> root.put(k, i);
                case Boolean b -> root.put(k, b);
                default -> root.put(k, v.toString());
            }
        });
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, JSON.writeValueAsBytes(root));
            dirty = false;
        } catch (final IOException | JacksonException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the settings [file=" + file + ']', e);
        }
    }
}
