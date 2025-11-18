package io.questdb.desktop.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


public class StoreEntry implements UniqueId<String>, Comparable<StoreEntry> {

    private static final @NotNull Comparator<String> COMPARING = (k1, k2) -> {
        String[] k1Parts = k1.split("\\.");
        String[] k2Parts = k2.split("\\.");
        if (k1Parts.length != k2Parts.length) {
            return Integer.compare(k1Parts.length, k2Parts.length);
        } else if (2 == k1Parts.length) {
            if (Objects.equals(k1Parts[0], k2Parts[0])) {
                return k1Parts[1].compareTo(k2Parts[1]);
            }
        }
        return k1.compareTo(k2);
    };

    private final @NotNull Map<String, String> attrs;
    private @NotNull volatile String name;

    public StoreEntry(final @NotNull String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        this.name = name;
        attrs = new TreeMap<>();
    }

    public StoreEntry(final @NotNull StoreEntry other) {
        name = other.name;
        attrs = other.attrs;
    }

    public @NotNull String getName() {
        return name;
    }

    public void setName(final @NotNull String name) {
        this.name = name;
    }

    public @Nullable String getAttr(final @NotNull String attrName) {
        return attrs.get(attrName);
    }

    public @Nullable String getAttr(final @NotNull UniqueId<String> attr) {
        return attrs.get(attr.getUniqueId());
    }

    public void setAttr(final @NotNull UniqueId<String> attr, final @NotNull String value) {
        setAttr(attr, value, "");
    }

    public void setAttr(final @NotNull UniqueId<String> attr, final @Nullable String value, final @NotNull String defaultValue) {
        attrs.put(attr.getUniqueId(), null == value || value.isEmpty() ? defaultValue : value);
    }

    public void setAttr(final @NotNull String attrName, final @Nullable String value, final @NotNull String defaultValue) {
        attrs.put(attrName, value == null || value.isEmpty() ? defaultValue : value);
    }

    public void setAttr(final @NotNull String attrName, final @NotNull String value) {
        attrs.put(attrName, value);
    }

    @Override
    public boolean equals(final @Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof StoreEntry that) {
            return name.equals(that.name) && attrs.equals(that.attrs);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, attrs);
    }

    @Override
    public int compareTo(final @NotNull StoreEntry that) {
        return COMPARING.compare(getUniqueId(), that.getUniqueId());
    }

    @Override
    public @NotNull String getUniqueId() {
        return String.format("%s.%s", name, attrs);
    }

    @Override
    public @NotNull String toString() {
        return getUniqueId();
    }
}
