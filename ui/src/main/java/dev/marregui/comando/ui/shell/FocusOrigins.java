package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.state.LowerTab;

import java.awt.Component;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where the keyboard was when each panel or view opened, so that closing it gives the keyboard back there (record 112):
 * a document opened from another by a double click, closed, hands the keyboard back to the one that opened it, and
 * opened from that one again to the next, so openings chain. A panel is keyed {@code panel:<id>}, a view
 * {@code view:<id>}; the component outside both (the Files tab, the Session tab's transcript) has no key. Kept for the
 * window's life, the latest {@link #KEPT}. EDT only.
 */
final class FocusOrigins {

    /** The openings remembered, the latest. */
    static final int KEPT = 256;

    /**
     * Where the keyboard was when a panel or view opened: the component, the panel or view that held it ({@code null}
     * outside both), and the lower tab in front then.
     */
    record Origin(Component owner, String ownerKey, LowerTab lowerTab) {
    }

    private final Map<String, Origin> origins = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Origin> eldest) {
            return size() > KEPT;
        }
    };
    /** The panels and views open at the last render; null before the first. */
    private Set<String> open;

    /**
     * A render starts: {@code nowOpen} are the panels and views it shows, {@code owner} holds the keyboard, inside
     * {@code ownerKey}, with {@code lowerTab} in front, and {@code front} is the panel in front before this render.
     * What opened since the last render remembers this origin; when what held the keyboard, or the panel in front,
     * closed, the answer is where the keyboard goes back: its origin, or, when that is gone too, the origin's own, and
     * so on, the first that {@code alive} accepts. Null when nothing goes back.
     */
    Origin onRender(Set<String> nowOpen, Component owner, String ownerKey, LowerTab lowerTab, String front, Predicate<Origin> alive) {
        Set<String> before = open;
        open = Set.copyOf(nowOpen);
        if (before == null) {
            return null;
        }
        for (String k : nowOpen) {
            if (!before.contains(k) && owner != null && !k.equals(ownerKey)) {
                origins.put(k, new Origin(owner, ownerKey, lowerTab));
            }
        }
        String closed = ownerKey != null && before.contains(ownerKey) && !nowOpen.contains(ownerKey) ? ownerKey
                : front != null && before.contains(front) && !nowOpen.contains(front) ? front : null;
        if (closed == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        Origin o = origins.get(closed);
        while (o != null && !alive.test(o)) {
            if (o.ownerKey() == null || !seen.add(o.ownerKey())) {
                return null;
            }
            o = origins.get(o.ownerKey());
        }
        return o;
    }

    /** The origin remembered for {@code key}; for tests. */
    Origin of(String key) {
        return origins.get(key);
    }
}
