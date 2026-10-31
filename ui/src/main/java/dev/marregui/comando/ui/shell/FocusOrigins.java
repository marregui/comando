package dev.marregui.comando.ui.shell;

import java.awt.Component;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.marregui.comando.core.state.LowerTab;

/**
 * The return path (record 112): each panel and view remembers where the keyboard was just before the user last
 * arrived at it, so that closing it returns there, as an IDE's editor tabs do. Opening a document from another is one
 * arrival among others: A opens B, B opens C; closing C returns to B, closing B to A. A user who went from B to main
 * and back to B returns to main when B closes. Closing a tab the user is not at moves nothing. The place the user is
 * at is a view the keyboard is in, else the panel in front (its lower tabs included). A panel is keyed
 * {@code panel:<id>}, a view {@code view:<id>}. Kept for the window's life, the latest {@link #KEPT}. EDT only.
 */
final class FocusOrigins {

    /** How many arrivals are remembered, the latest first. */
    static final int KEPT = 256;

    /**
     * Where the keyboard was: the component, the panel or view that held it ({@code null} outside both), the lower tab
     * in front then, and the panel in front then, whose session the lower tabs showed.
     */
    record Origin(Component owner, String ownerKey, LowerTab lowerTab, String front) {
    }

    private final Map<String, Origin> cameFrom = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Origin> eldest) {
            return size() > KEPT;
        }
    };
    /** Where an arrival the shell is about to make comes from, read at its request; taken by that arrival. */
    private final Map<String, Origin> pending = new HashMap<>();
    /** The panels and views open at the last render; null before the first. */
    private Set<String> open;
    /** The place the user was at, at the last render. */
    private String at;

    /**
     * The shell is about to bring {@code key} to the user, from {@code origin}, read at the request (a file opened, a
     * panel brought forward or back for it): that is where its arrival comes from, more exactly than the render that
     * shows it can tell. Nothing when {@code origin} is null.
     */
    void opened(final String key, final Origin origin) {
        if (origin != null && !key.equals(origin.ownerKey())) {
            pending.put(key, origin);
        }
    }

    /**
     * A render starts: {@code nowOpen} are the panels and views it shows, {@code before} is where the keyboard was
     * before this render's change, and {@code now} is the place the user is at with it. Arriving at a new place
     * remembers {@code before} (or the origin told for it) as where it came from; when the place the user was at
     * closed, the answer is where it came from, or, when that is gone too, where that came from, and so on, the first
     * that {@code alive} accepts. Null when nothing goes back.
     */
    Origin onRender(final Set<String> nowOpen, final Origin before, final String now, final Predicate<Origin> alive) {
        final Set<String> wasOpen = open;
        final String was = at;
        open = Set.copyOf(nowOpen);
        at = now;
        if (wasOpen == null) {
            pending.clear();
            return null;
        }
        final boolean closed = was != null && !nowOpen.contains(was);
        if (now != null && !now.equals(was)) {
            final Origin told = pending.remove(now);
            // a place reached because the one the user was at closed keeps its own way back
            final Origin from = told != null ? told : closed ? null : before;
            if (from != null && !now.equals(from.ownerKey()) && !now.equals(from.front())) {
                cameFrom.put(now, from);
            }
        }
        if (!closed) {
            return null;
        }
        final Set<String> seen = new HashSet<>();
        Origin o = cameFrom.get(was);
        while (o != null && !alive.test(o)) {
            final String up = o.ownerKey() != null ? o.ownerKey() : o.front();
            if (up == null || !seen.add(up)) {
                return null;
            }
            o = cameFrom.get(up);
        }
        return o;
    }

    /** Where {@code key} was last arrived at from; for tests. */
    Origin of(final String key) {
        return cameFrom.get(key);
    }
}
