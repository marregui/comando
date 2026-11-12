package dev.marregui.comando.ui.panels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;

/**
 * The folders the sidebar's folder tabs have open, as one {@link Intent.WatchFolders}: the intent names every folder to
 * read and replaces the last, so each tab gives its share to a {@link Part} and the watch sends their union, in the
 * order the parts were made, when it changed, or when a part asks for it again and the union is not empty. Event
 * dispatch thread only.
 */
final class FolderWatch {

    private final IntentSink intents;
    private final List<Share> shares = new ArrayList<>();
    private List<String> sentNames = List.of();
    private Map<String, List<String>> sentOpen = Map.of();

    FolderWatch(final IntentSink intents) {
        this.intents = intents;
    }

    /** A new share, empty until its tab watches. */
    Part part() {
        final Share s = new Share();
        shares.add(s);
        return s;
    }

    private void send(final boolean force) {
        final Set<String> names = new LinkedHashSet<>();
        final Map<String, List<String>> open = new HashMap<>();
        for (final Share s : shares) {
            names.addAll(s.names);
            s.open.forEach((name, inside) -> open.computeIfAbsent(name, _ -> new ArrayList<>()).addAll(inside));
        }
        final List<String> union = List.copyOf(names);
        if (!union.equals(sentNames) || !open.equals(sentOpen) || force && !union.isEmpty()) {
            sentNames = union;
            sentOpen = open;
            intents.dispatch(new Intent.WatchFolders(union, open));
        }
    }

    /** One tab's share of the watch. */
    interface Part {
        /** The tab's open folders and, by folder name, their open subfolders; {@code force} sends them again. */
        void watch(List<String> names, Map<String, List<String>> open, boolean force);
    }

    private final class Share implements Part {
        private List<String> names = List.of();
        private Map<String, List<String>> open = Map.of();

        @Override
        public void watch(final List<String> names, final Map<String, List<String>> open, final boolean force) {
            this.names = List.copyOf(names);
            this.open = Map.copyOf(open);
            send(force);
        }
    }
}
