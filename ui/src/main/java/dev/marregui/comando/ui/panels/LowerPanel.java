package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;
import dev.marregui.comando.ui.widgets.approval.ApprovalInbox;
import dev.marregui.comando.ui.widgets.transcript.TranscriptView;
import dev.marregui.comando.ui.widgets.transcript.TranscriptWidget;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lower panel: Session (the focused panel's transcript), Approvals (every session), Terminal (the focused
 * panel's shell), Results, then one tab per view placed below. The selected tab follows the state, and selecting a tab updates the state.
 * Results and Session carry a dot when something new arrived while another tab was in front. While Session is in front,
 * a Clear button at the end of the tab strip empties it; the conversation goes on, whole in Claude Code's files.
 */
public final class LowerPanel {

    // the session's tabs first; the database's results last
    static final int SESSION = 0;
    static final int APPROVALS = 1;
    static final int TERMINAL = 2;
    static final int RESULTS = 3;
    /** The fixed tabs; placed views follow them. */
    static final int FIXED_TABS = 4;

    private final WidgetContext context;
    private final TranscriptRegistry transcripts;
    private final JTabbedPane tabs = named(new JTabbedPane(), "lower");
    private final ResultsView results;
    private final TranscriptWidget transcript;
    private final ApprovalInbox inbox;
    private final ViewTabs views;
    private final TerminalTabs terminals = new TerminalTabs();
    private AppState state;
    private boolean rendering;
    private String transcriptSession = "";
    /** The transcript version the user last saw on the Session tab, by session. */
    private final Map<String, Long> seenTranscript = new HashMap<>();
    /** The run the user last saw on the Results tab, by panel: request id and status. */
    private final Map<PanelId, String> seenRun = new HashMap<>();
    /** The tab in front before the last change, so the Approvals tab knows when it comes forward. EDT only. */
    private int lastSelected = SESSION;

    public LowerPanel(WidgetContext context, ResultRegistry results, TranscriptRegistry transcripts, WidgetRegistry registry) {
        this.context = context;
        this.transcripts = transcripts;
        this.results = new ResultsView(results);
        this.transcript = new TranscriptWidget(context);
        this.inbox = new ApprovalInbox(context);
        tabs.addTab("Session", Icons.get("session"), transcript.component());
        tabs.addTab("Approvals", Icons.get("check"), inbox.component());
        tabs.addTab("Terminal", Icons.get("terminal"), terminals.component());
        tabs.addTab("Results", Icons.get("table"), this.results.component());
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        views = new ViewTabs(tabs, FIXED_TABS, Placement.LOWER, registry, context);
        // no buttons at the strip's end: the theme is chosen at the top right, Clear from the palette (record 104)
        tabs.addChangeListener(e -> {
            if (rendering) {
                return;
            }
            int i = tabs.getSelectedIndex();
            LowerTab tab = switch (i) {
                case RESULTS -> LowerTab.RESULTS;
                case SESSION -> LowerTab.SESSION;
                case APPROVALS -> LowerTab.APPROVALS;
                case TERMINAL -> LowerTab.TERMINAL;
                default -> {
                    long id = views.viewAt(i);
                    yield id < 0 ? null : LowerTab.view(id);
                }
            };
            if (tab != null) {
                context.intents().dispatch(new Intent.SelectLowerTab(tab));
            }
        });
        // the Approvals tab comes forward at its top, the oldest request first, however it was brought forward
        tabs.addChangeListener(e -> {
            int i = tabs.getSelectedIndex();
            if (i == APPROVALS && lastSelected != APPROVALS) {
                inbox.shown();
            }
            lastSelected = i;
        });
    }

    public JComponent component() {
        return tabs;
    }

    public ResultsView results() {
        return results;
    }

    public ApprovalInbox inbox() {
        return inbox;
    }

    public TerminalTabs terminals() {
        return terminals;
    }

    public TranscriptWidget transcript() {
        return transcript;
    }

    /** The component of the tab in front: a fixed tab's view or a placed view. */
    public Component selected() {
        return tabs.getSelectedComponent();
    }

    /** The placed view in front, or -1 when a fixed tab (Results, Session, Approvals, Terminal) is in front. */
    public long selectedView() {
        int i = tabs.getSelectedIndex();
        return i < FIXED_TABS ? -1 : views.viewAt(i);
    }

    /** The placed view whose tab holds {@code c}, or -1. */
    public long viewHolding(Component c) {
        for (int i = FIXED_TABS; i < tabs.getTabCount(); i++) {
            if (SwingUtilities.isDescendingFrom(c, tabs.getComponentAt(i))) {
                return views.viewAt(i);
            }
        }
        return -1;
    }

    /** Selects the tab {@code delta} places from the one in front, wrapping around. */
    public void selectRelative(int delta) {
        int n = tabs.getTabCount();
        if (n > 0) {
            tabs.setSelectedIndex(Math.floorMod(tabs.getSelectedIndex() + delta, n));
        }
    }

    /** The session whose transcript the Session tab shows. */
    public String transcriptSession() {
        return transcriptSession;
    }

    public void render(AppState s) {
        rendering = true;
        try {
            PanelState focused = s.focusedPanel();
            results.render(s.runOf(focused.id()));
            renderTranscript(focused.session());
            String shown = focused.session();
            transcript.setWaiting(s.approvals().stream().filter(a -> a.session().equals(shown)).toList());
            transcript.setBackgroundTasks(s.session(shown).map(SessionState::tasks).orElse(List.of()));
            inbox.render(s.approvals());
            int pending = s.approvals().size();
            tabs.setTitleAt(APPROVALS, pending == 0 ? "Approvals" : "Approvals (" + pending + ")");
            tabs.setForegroundAt(APPROVALS, pending == 0 ? null : Theme.ATTENTION);
            views.render(s.views());
            int want = switch (s.lowerTab().kind()) {
                case RESULTS -> RESULTS;
                case SESSION -> SESSION;
                case APPROVALS -> APPROVALS;
                case TERMINAL -> TERMINAL;
                case VIEW -> views.indexOf(s.lowerTab().viewId());
            };
            if (want >= 0 && tabs.getSelectedIndex() != want) {
                tabs.setSelectedIndex(want);
            }
            terminals.render(s, s.lowerTab().kind() == LowerTab.Kind.TERMINAL);
            markResults(s);
            markSession();
            state = s;
        } finally {
            rendering = false;
        }
    }

    /** A run of the focused panel that ended while Results was not in front gets a dot until Results is shown. */
    private void markResults(AppState s) {
        SqlRun run = s.runOf(s.focused()).orElse(null);
        boolean showing = tabs.getSelectedIndex() == RESULTS;
        String now = run == null ? "" : run.requestId() + ":" + run.status();
        if (showing || run == null) {
            seenRun.put(s.focused(), now);
        } else {
            seenRun.putIfAbsent(s.focused(), now); // a panel seen for the first time has nothing new
        }
        boolean unseen = run != null && !run.status().isActive() && !now.equals(seenRun.get(s.focused()));
        title(RESULTS, "Results", unseen);
    }

    /** New transcript content while Session is not in front gets a dot until Session is shown. */
    private void markSession() {
        Transcript t = transcriptSession.isEmpty() ? null : transcripts.of(transcriptSession);
        long version = t == null ? 0 : t.version();
        boolean showing = tabs.getSelectedIndex() == SESSION;
        Long seen = seenTranscript.get(transcriptSession);
        if (showing || seen == null) {
            seenTranscript.put(transcriptSession, version);
        }
        boolean unseen = !showing && seen != null && version > seen;
        title(SESSION, "Session", unseen);
    }

    private void title(int index, String name, boolean marked) {
        String title = marked ? name + " ●" : name;
        if (!tabs.getTitleAt(index).equals(title)) {
            tabs.setTitleAt(index, title);
            tabs.setForegroundAt(index, marked ? Theme.tint().body : null);
        }
    }

    /** Re-renders the Session tab; called when the shown transcript grew. */
    public void renderTranscript(String session) {
        transcriptSession = session;
        transcript.render(session.isEmpty() ? null : new TranscriptView(session, transcripts.of(session)));
        markSession();
    }

    /**
     * Whether the Session tab can be cleared, from the palette (record 104): it shows a session's transcript, which has
     * blocks, and no turn writes more. The conversation itself goes on.
     */
    public boolean canClearTranscript() {
        boolean busy = state != null && state.session(transcriptSession).map(SessionState::isBusy).orElse(false);
        return !transcriptSession.isEmpty() && !busy && transcripts.of(transcriptSession).size() > 0;
    }

    /** Empties the Session tab, when {@link #canClearTranscript()}. */
    public void clearTranscript() {
        if (canClearTranscript()) {
            context.intents().dispatch(new Intent.ClearTranscript(transcriptSession));
        }
    }

    private static JTabbedPane named(JTabbedPane tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
