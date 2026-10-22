package dev.marregui.comando.ui.widgets.console;

import dev.marregui.comando.core.console.Console;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.Coalescer;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.text.TextSearch;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Searchable;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Locale;
import java.util.Map;

/**
 * A command run: its output as it arrives, a header with the state and a stop button, and, after a
 * build, the test results as a tree. A failed test opens at its line with a double click and can be
 * sent to the panel's Claude session.
 */
public final class ConsoleWidget implements Widget<ViewSpec.Console>, Searchable, Copyable {

    private final WidgetContext context;
    private final ConsoleRegistry consoles;
    private final RSyntaxTextArea area = Editors.viewer("");
    private final RTextScrollPane scroll = Editors.scroll(area, false);
    private final TextSearch search = new TextSearch(area);
    private final TreeView tests = new TreeView();
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel command = new JLabel();
    private final JLabel status = new JLabel();
    private final JButton stop = new JButton("Stop", Icons.get("stop"));
    private final JButton sendFailures = new JButton("Send failures to Claude", Icons.get("claude"));
    private final Coalescer pull = new Coalescer(this::pull);
    private final Runnable listener = pull::request;
    private ViewSpec.Console spec;
    private Console console;
    private int shown;

    public ConsoleWidget(WidgetContext context, ConsoleRegistry consoles) {
        this.context = context;
        this.consoles = consoles;
        area.setLineWrap(false);
        JPanel header = new JPanel(new BorderLayout(Theme.GAP, 0));
        header.setBorder(BorderFactory.createEmptyBorder(4, Theme.PAD, 4, Theme.PAD));
        command.setFont(Theme.mono());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        right.add(status);
        right.add(sendFailures);
        right.add(stop);
        header.add(command, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        stop.addActionListener(e -> {
            if (spec != null) {
                context.intents().dispatch(new Intent.CancelCommand(spec.runId()));
            }
        });
        sendFailures.addActionListener(e -> sendAll());
        sendFailures.setVisible(false);
        tests.onActivate(n -> {
            if (n.link().startsWith(SourceLinks.SCHEME)) {
                context.intents().dispatch(new Intent.OpenFile("", SourceLinks.path(n.link()), SourceLinks.line(n.link())));
            }
        });
        tests.onMenu((n, e) -> {
            String key = failureKey(n);
            if (key != null) {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem send = new JMenuItem("Send this failure to Claude", Icons.get("claude"));
                send.addActionListener(a -> send(Map.of(key, spec.failures().get(key))));
                menu.add(send);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        split.setResizeWeight(0.62);
        split.setBorder(null);
        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        Capabilities.mark(root, this);
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void render(ViewSpec.Console view) {
        if (spec == null || spec.runId() != view.runId()) {
            if (console != null) {
                console.removeListener(listener);
            }
            console = consoles.of(view.runId());
            console.addListener(listener);
            area.setText("");
            shown = 0;
        }
        spec = view;
        command.setText(view.command());
        stop.setEnabled(view.state() == ViewSpec.RunState.RUNNING);
        stop.setVisible(view.state() == ViewSpec.RunState.RUNNING);
        String seconds = String.format(Locale.ROOT, "%.1f s", view.millis() / 1000.0);
        switch (view.state()) {
            case RUNNING -> {
                status.setText("running");
                status.setForeground(Theme.warn());
            }
            case PASSED -> {
                status.setText("passed in " + seconds);
                status.setForeground(Theme.OK);
            }
            case FAILED -> {
                status.setText("failed, exit " + view.exitCode() + ", in " + seconds);
                status.setForeground(Theme.DANGER);
            }
            case CANCELLED -> {
                status.setText("stopped");
                status.setForeground(Theme.MUTED);
            }
        }
        sendFailures.setVisible(!view.failures().isEmpty());
        boolean hasTests = !view.tests().label().isEmpty();
        if (hasTests) {
            tests.setRoot(view.tests(), 2);
            if (split.getParent() == null) {
                root.remove(scroll);
                split.setLeftComponent(scroll);
                split.setRightComponent(tests.component());
                root.add(split, BorderLayout.CENTER);
                root.revalidate();
            }
        } else if (split.getParent() != null) {
            root.remove(split);
            root.add(scroll, BorderLayout.CENTER);
            root.revalidate();
        }
        pull();
    }

    /** Stops following the run's output; the view was closed. */
    @Override
    public void unmount() {
        if (console != null) {
            console.removeListener(listener);
            console = null;
        }
    }

    /** Appends what the console has beyond what is shown, following the end when the view is at the end. */
    private void pull() {
        if (console == null) {
            return;
        }
        String more = console.text(shown);
        if (more.isEmpty()) {
            return;
        }
        JScrollBar bar = scroll.getVerticalScrollBar();
        boolean follow = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 30;
        area.append(more);
        shown += more.length();
        if (follow) {
            area.setCaretPosition(area.getDocument().getLength());
        }
    }

    /** {@code Class.method} for a failed test node, null for any other node. */
    private String failureKey(ViewSpec.TreeNode n) {
        if (spec == null || !n.label().startsWith("✗ ")) {
            return null;
        }
        ViewSpec.TreeNode parent = tests.parentOf(n);
        String key = parent == null ? null : parent.label() + "." + n.label().substring(2);
        return key != null && spec.failures().containsKey(key) ? key : null;
    }

    private void sendAll() {
        if (spec != null && !spec.failures().isEmpty()) {
            send(spec.failures());
        }
    }

    private void send(Map<String, String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(failures.size() == 1 ? "This test failed" : failures.size() + " tests failed").append(" in `").append(spec.command())
                .append("`:\n");
        failures.forEach((k, v) -> sb.append("\n").append(k).append("\n```\n").append(v).append("\n```\n"));
        // to the session of the panel the build was started from, not whichever panel has the focus now
        PanelId panel = spec.panel().isEmpty() ? null : new PanelId(spec.panel());
        context.intents().dispatch(new Intent.RunText(panel, sb.toString(), Target.CLAUDE));
    }

    // ---- capabilities

    @Override
    public int find(String text, boolean forward) {
        return search.find(text, forward);
    }

    @Override
    public void clearFind() {
        search.clear();
    }

    @Override
    public void rewindFind() {
        search.rewind();
    }

    @Override
    public String copyText() {
        String s = area.getSelectedText();
        return s != null ? s : area.getText();
    }
}
