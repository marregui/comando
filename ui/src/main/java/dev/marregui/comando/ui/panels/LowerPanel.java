package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;
import dev.marregui.comando.ui.widgets.approval.ApprovalInbox;
import dev.marregui.comando.ui.widgets.transcript.TranscriptView;
import dev.marregui.comando.ui.widgets.transcript.TranscriptWidget;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;

/**
 * The lower panel: Results, Session (the focused panel's transcript), Approvals (every session), Terminal
 * (the focused panel's shell), then one tab per view placed below. The selected tab follows the state, and selecting a tab updates the state.
 */
public final class LowerPanel {

    static final int RESULTS = 0;
    static final int SESSION = 1;
    static final int APPROVALS = 2;
    static final int TERMINAL = 3;

    private final WidgetContext context;
    private final TranscriptRegistry transcripts;
    private final JTabbedPane tabs = named(new JTabbedPane(), "lower");
    private final ResultsView results;
    private final TranscriptWidget transcript;
    private final ApprovalInbox inbox;
    private final ViewTabs views;
    private final TerminalTabs terminals = new TerminalTabs();
    private boolean rendering;
    private String transcriptSession = "";

    public LowerPanel(WidgetContext context, ResultRegistry results, TranscriptRegistry transcripts, WidgetRegistry registry) {
        this.context = context;
        this.transcripts = transcripts;
        this.results = new ResultsView(results);
        this.transcript = new TranscriptWidget(context);
        this.inbox = new ApprovalInbox(context);
        tabs.addTab("Results", Icons.get("table"), this.results.component());
        tabs.addTab("Session", Icons.get("session"), transcript.component());
        tabs.addTab("Approvals", Icons.get("check"), inbox.component());
        tabs.addTab("Terminal", Icons.get("terminal"), terminals.component());
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        views = new ViewTabs(tabs, 4, Placement.LOWER, registry, context);
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
            inbox.render(s.approvals());
            int pending = s.approvals().size();
            tabs.setTitleAt(APPROVALS, pending == 0 ? "Approvals" : "Approvals (" + pending + ")");
            tabs.setForegroundAt(APPROVALS, pending == 0 ? null : dev.marregui.comando.ui.kit.theme.Theme.WARN);
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
        } finally {
            rendering = false;
        }
    }

    /** Re-renders the Session tab; called when the shown transcript grew. */
    public void renderTranscript(String session) {
        transcriptSession = session;
        transcript.render(session.isEmpty() ? null : new TranscriptView(session, transcripts.of(session)));
    }

    private static JTabbedPane named(JTabbedPane tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
