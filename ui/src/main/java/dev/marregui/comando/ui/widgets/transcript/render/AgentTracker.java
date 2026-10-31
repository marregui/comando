package dev.marregui.comando.ui.widgets.transcript.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;

/**
 * The transcript's tool cards by id and what they run: the running cards, the running top-level sub-agents, the
 * sub-agents launched into the background, and the cards held up by an approval. EDT only.
 */
final class AgentTracker {

    private static final String LOCAL_AGENT = "local_agent";
    /** The most ids kept across rebuilds; the oldest go first. */
    private static final int KEPT_IDS = 2048;

    private final Map<String, Items.ToolItem> cards = new HashMap<>();
    private final Set<Items.ToolItem> running = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The running top-level sub-agent cards, in the order they started. */
    private final Set<Items.ToolItem> runningAgents = new LinkedHashSet<>();
    /** The cards whose result launched a sub-agent into the background. */
    private final Set<Items.ToolItem> launched = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The session's background sub-agents now open, and every one this canvas has seen open, by id. */
    private Set<String> openTasks = Set.of();
    private final Set<String> seenTasks = Collections.newSetFromMap(bounded());
    /**
     * Each card's times (see {@link Items.ToolItem}) by tool-use id, kept across {@link #clear}: a session switch or
     * "Show earlier" rebuilds the cards, and a call five minutes in still reads five minutes.
     */
    private final Map<String, long[]> times = bounded();
    private Runnable agentsChanged = () -> { };
    private Set<Items.ToolItem> waitingCards = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Approval> waitingFor = List.of();
    private final LongSupplier clock;
    /** The card or hook group an item sits in; null at the top level. */
    private final UnaryOperator<Item> parentOf;
    /** Runs when the set of running cards may have changed. */
    private final Runnable runningChanged;
    private final Runnable repaint;

    AgentTracker(final LongSupplier clock, final UnaryOperator<Item> parentOf, final Runnable runningChanged, final Runnable repaint) {
        this.clock = clock;
        this.parentOf = parentOf;
        this.runningChanged = runningChanged;
        this.repaint = repaint;
    }

    /** Forgets the cards; their times and the tasks seen stay. */
    void clear() {
        cards.clear();
        running.clear();
        runningAgents.clear();
        launched.clear();
        waitingCards.clear();
    }

    /** A new card for {@code call}, at transcript index {@code index}, with the times its id had before a rebuild. */
    Items.ToolItem newCard(final Block.ToolCall call, final int index) {
        return new Items.ToolItem(call, index, times.getOrDefault(call.toolUseId(), Items.ToolItem.newTimes()));
    }

    /** The card {@code card} was appended. */
    void added(final Items.ToolItem card) {
        cards.put(card.call().toolUseId(), card);
        track(card);
    }

    /** {@code card} was appended inside {@code parent}: it counts in every card it sits in, up to the top level. */
    void nested(final Items.ToolItem parent, final Items.ToolItem card) {
        final String label = Items.ToolItem.lastLabel(card.call());
        for (Item p = parent; p != null; p = parentOf.apply(p)) {
            if (p instanceof Items.ToolItem t) {
                t.toolAdded(card.call().toolUseId(), label);
            }
        }
    }

    /** The block of {@code card} was replaced. */
    void updated(final Items.ToolItem card) {
        track(card);
        lastToolChanged(card);
        if (!waitingFor.isEmpty() || !waitingCards.isEmpty()) {
            resolveWaiting();
        }
    }

    /** A block was appended: an approval waiting may now find its card. */
    void appended() {
        if (!waitingFor.isEmpty()) {
            resolveWaiting();
        }
    }

    Items.ToolItem card(final String toolUseId) {
        return cards.get(toolUseId);
    }

    boolean hasRunning() {
        return !running.isEmpty();
    }

    /** Once a second while a card runs: a launch's grace ends with no event to say so. */
    void tick() {
        if (!launched.isEmpty()) {
            retrackLaunched();
        }
    }

    /**
     * Keeps the set of active cards, which drives the elapsed-time repaint, the running top-level sub-agents, and the
     * cards that launched a sub-agent into the background.
     */
    private void track(final Items.ToolItem card) {
        final long now = clock.getAsLong();
        card.launchSeen(now);
        if (!card.launchedAgent().isEmpty()) {
            launched.add(card);
            card.setBackground(inBackground(card, now)); // before the times: a launch is not the end
        } else {
            launched.remove(card);
        }
        card.seen(now);
        if (card.call().isSubagent() && card.runningSince() >= 0) {
            times.putIfAbsent(card.call().toolUseId(), card.times());
        }
        final boolean agent = card.call().isSubagent() && card.call().parent().isEmpty();
        if (card.isActive()) {
            running.add(card);
            if (agent) {
                runningAgents.add(card);
            }
        } else {
            running.remove(card);
            runningAgents.remove(card);
        }
        runningChanged.run();
    }

    /**
     * A sub-agent launched into the background works while the session lists it among its open tasks. Its launch can
     * reach the canvas before the list that holds it: a launch seen live counts as working for
     * {@link TranscriptCanvas#LAUNCH_GRACE_MILLIS}, unless the list already held it and let it go.
     */
    private boolean inBackground(final Items.ToolItem card, final long now) {
        final String id = card.launchedAgent();
        if (openTasks.contains(id)) {
            return true;
        }
        return !seenTasks.contains(id) && card.launchedAt() >= 0 && now - card.launchedAt() < TranscriptCanvas.LAUNCH_GRACE_MILLIS;
    }

    /** The session's open background tasks: a card whose result launched one runs until it leaves the list. */
    void setBackgroundTasks(final List<BackgroundTask> tasks) {
        final Set<String> open = new HashSet<>();
        for (final BackgroundTask t : tasks) {
            if (t.kind().equals(LOCAL_AGENT)) {
                open.add(t.id());
            }
        }
        if (open.equals(openTasks)) {
            return;
        }
        openTasks = open;
        seenTasks.addAll(open);
        retrackLaunched();
    }

    /** Re-reads whether each launched sub-agent still works; repaints, and tells the listener, when one changed. */
    private void retrackLaunched() {
        boolean changed = false;
        for (final Items.ToolItem card : List.copyOf(launched)) {
            final boolean was = card.isActive();
            track(card);
            changed |= was != card.isActive();
        }
        if (changed) {
            repaint.run();
            agentsChanged.run();
        }
    }

    void onAgentsChanged(final Runnable listener) {
        agentsChanged = listener;
    }

    private static <V> Map<String, V> bounded() {
        return new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, V> eldest) {
                return size() > KEPT_IDS;
            }
        };
    }

    /** A nested call changed (its input arrived): the cards showing it as their latest call show the new summary. */
    private void lastToolChanged(final Items.ToolItem card) {
        String label = null;
        for (Item p = parentOf.apply(card); p != null; p = parentOf.apply(p)) {
            if (p instanceof Items.ToolItem t && t.isLastTool(card.call().toolUseId())) {
                if (label == null) {
                    label = Items.ToolItem.lastLabel(card.call());
                }
                t.lastToolChanged(label);
            }
        }
    }

    /** The running top-level sub-agent cards, in the order they started. */
    List<TranscriptCanvas.RunningAgent> runningAgents() {
        final List<TranscriptCanvas.RunningAgent> out = new ArrayList<>(runningAgents.size());
        for (final Items.ToolItem card : runningAgents) {
            final Block.ToolCall c = card.call();
            out.add(new TranscriptCanvas.RunningAgent(c.toolUseId(), card.summary(), card.runningSince(), card.launchedAgent()));
        }
        return out;
    }

    // ---- approvals

    /** The approval cards that hold up this session's tool calls. */
    void setWaiting(final List<Approval> approvals) {
        waitingFor = List.copyOf(approvals);
        resolveWaiting();
    }

    private void resolveWaiting() {
        final Set<Items.ToolItem> now = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Approval a : waitingFor) {
            Items.ToolItem card = null;
            if (!a.toolUseId().isEmpty()) {
                card = cards.get(a.toolUseId());
            } else {
                for (final Items.ToolItem c : running) {
                    if (isTool(c.call().name(), a.toolName()) && (card == null || c.order() > card.order())) {
                        card = c;
                    }
                }
            }
            if (card != null && card.isRunning()) {
                now.add(card);
            }
        }
        if (now.equals(waitingCards)) {
            return;
        }
        for (final Items.ToolItem c : waitingCards) {
            c.setWaiting(now.contains(c));
        }
        for (final Items.ToolItem c : now) {
            c.setWaiting(true);
        }
        waitingCards = now;
        repaint.run();
    }

    private static boolean isTool(final String callName, final String toolName) {
        return !toolName.isEmpty() && (callName.equals(toolName) || callName.endsWith("__" + toolName));
    }
}
