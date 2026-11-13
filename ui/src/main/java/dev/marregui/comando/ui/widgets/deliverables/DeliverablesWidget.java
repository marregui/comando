package dev.marregui.comando.ui.widgets.deliverables;

import java.awt.BorderLayout;
import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.Coalescer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.HitMarks;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.Previews;
import dev.marregui.comando.ui.kit.text.Tooltips;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;

/**
 * The files the session with the keyboard wrote (record 143), as a view of the side pane: one row per file its Write,
 * Edit, MultiEdit and NotebookEdit calls wrote, in the order it first wrote each, named by the file and its folder in
 * its repository. A file not opened yet is gold, with the folder tabs' mark of a new file (record 76), and plain once
 * opened. Moving the selection shows the file in the workspace's preview tab without taking the keyboard from the
 * rows; Enter or a double click keeps the tab, and the file counts as opened. The rows follow the session as it
 * writes; while a panel that is no session's has the keyboard, the last session's rows stay.
 */
public final class DeliverablesWidget implements Widget<ViewSpec.Deliverables>, Copyable {

    private final JPanel body = new JPanel(new BorderLayout());
    private final Coalescer follow = new Coalescer(this::follow);
    private final JLabel foot = Plain.only(new JLabel(" "));
    private final Focus focus;
    private final OpenedFiles opened;
    private final Previews previews;
    private final Coalescer pull = new Coalescer(this::read);
    private final Runnable listener = pull::request;
    private final Runnable stopFocus;
    private final Runnable stopOpened;
    /** The file of each row's link. */
    private final Map<String, Path> targets = new HashMap<>();
    private final TranscriptRegistry transcripts;
    private final TreeView tree = new TreeView();
    private final Overlay overlay = new Overlay(tree.component());
    /** A change came while a read ran: one more read follows it. */
    private boolean again;
    private boolean built;
    /** The view closed: a change still queued for the EDT reads nothing. */
    private boolean closed;
    /** The reader of the session shown; null before a session had the keyboard. */
    private WrittenFiles reader;
    private boolean reading;
    /** True while rows are built: the selection they restore is not the user's move. */
    private boolean rendering;
    private List<WrittenFiles.Written> rows = List.of();
    private String session = "";
    private Transcript watched;

    public DeliverablesWidget(
            final Focus focus,
            final TranscriptRegistry transcripts,
            final OpenedFiles opened,
            final Previews previews
    ) {
        this.focus = focus;
        this.transcripts = transcripts;
        this.opened = opened;
        this.previews = previews;
        layOut();
        listen();
        Capabilities.mark(tree.component(), this);
        stopFocus = focus.listen(follow::request);
        stopOpened = opened.listen(tree::rowsChanged);
        follow();
    }

    @Override
    public JComponent component() {
        return body;
    }

    @Override
    public void render(final ViewSpec.Deliverables view) {
        // the view carries no data: the rows are this widget's
    }

    @Override
    public void unmount() {
        closed = true;
        stopFocus.run();
        stopOpened.run();
        if (watched != null) {
            watched.removeListener(listener);
            watched = null;
        }
        reader = null;
    }

    @Override
    public String copyText() {
        return tree.copyText();
    }

    @Override
    public boolean canCopy() {
        return !rows.isEmpty();
    }

    private void layOut() {
        tree.tree().setRootVisible(false);
        tree.tree().getAccessibleContext().setAccessibleName(ViewSpec.Deliverables.TITLE);
        tree.icons(this::icon);
        tree.colors(this::color);
        tree.trailing(this::mark);
        tree.tips(this::tip);
        foot.setForeground(Theme.MUTED);
        foot.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP, 2, Theme.GAP));
        body.add(overlay, BorderLayout.CENTER);
        body.add(foot, BorderLayout.SOUTH);
    }

    private void listen() {
        tree.onActivate(this::activate);
        tree.tree().addTreeSelectionListener(_ -> {
            final Path file = selectedFile();
            if (!rendering && file != null && tree.tree().getSelectionCount() == 1) {
                previews.preview(file, HitMarks.NONE, false);
            }
        });
    }

    // ---- following the session

    /** Watches the transcript of the session with the keyboard; a panel that is no session's keeps the one shown. */
    private void follow() {
        if (closed) {
            return;
        }
        final String focused = focus.session();
        if (focused.isEmpty()) {
            if (session.isEmpty()) {
                foot.setText(" ");
                overlay.showEmpty("No session has the keyboard.");
            }
            return;
        }
        final Transcript t = transcripts.of(focused);
        if (focused.equals(session) && t == watched) {
            return;
        }
        if (watched != null) {
            watched.removeListener(listener);
        }
        session = focused;
        watched = t;
        reader = new WrittenFiles(t);
        t.addListener(listener);
        show(List.of());
        read();
    }

    /** Reads the blocks the session added off the EDT, one read at a time; a change during a read reads once more. */
    private void read() {
        if (closed || reader == null) {
            return;
        }
        if (reading) {
            again = true;
            return;
        }
        reading = true;
        final WrittenFiles r = reader;
        UiErrands.start("deliverables-read", () -> {
            List<WrittenFiles.Written> found = null;
            try {
                found = r.read();
            } finally {
                // a failure is logged by the errand, and the next change reads again
                final List<WrittenFiles.Written> read = found;
                Edt.later(() -> arrived(r, read));
            }
        });
    }

    private void arrived(final WrittenFiles r, final List<WrittenFiles.Written> found) {
        reading = false;
        if (closed) {
            return;
        }
        if (r == reader && found != null) {
            show(found);
        }
        if (again) {
            again = false;
            read();
        }
    }

    // ---- the rows

    /** Shows {@code found} as the rows, keeping the file selected. */
    private void show(final List<WrittenFiles.Written> found) {
        if (!built || !found.equals(rows)) {
            build(found);
        }
        count();
    }

    private void build(final List<WrittenFiles.Written> found) {
        final Path selected = selectedFile();
        rows = found;
        targets.clear();
        final List<ViewSpec.TreeNode> leaves = new ArrayList<>(found.size());
        for (final WrittenFiles.Written w : found) {
            final String link = ViewSpec.TreeNode.fileLink(w.file().toString(), 0);
            targets.put(link, w.file());
            final Path name = w.file().getFileName();
            leaves.add(new ViewSpec.TreeNode(name == null ? w.file().toString() : name.toString(), w.folder(), link,
                    List.of()));
        }
        final ViewSpec.TreeNode root = new ViewSpec.TreeNode(ViewSpec.Deliverables.TITLE, "", leaves);
        rendering = true;
        try {
            if (built) {
                tree.update(root);
            } else {
                built = true;
                tree.setRoot(root, 1);
            }
            if (selected != null && !selected.equals(selectedFile())) {
                select(selected);
            }
        } finally {
            rendering = false;
        }
    }

    private void count() {
        if (rows.isEmpty()) {
            foot.setText(" ");
            overlay.showEmpty(session + " has written no file yet.");
            return;
        }
        overlay.showContent();
        foot.setText(String.format(Locale.ROOT, "%,d %s written by %s", rows.size(),
                rows.size() == 1 ? "file" : "files", session));
    }

    private Path selectedFile() {
        final ViewSpec.TreeNode n = tree.selected();
        return n == null ? null : targets.get(n.link());
    }

    /** Selects the row of {@code file}: the tree matches rows by their names, and two files may share one. */
    private void select(final Path file) {
        for (int i = 0; i < tree.tree().getRowCount(); i++) {
            final TreePath p = tree.tree().getPathForRow(i);
            if (file.equals(fileOf((DefaultMutableTreeNode) p.getLastPathComponent()))) {
                tree.tree().setSelectionPath(p);
                tree.tree().scrollPathToVisible(p);
                return;
            }
        }
    }

    private void activate(final ViewSpec.TreeNode n) {
        final Path file = n == null ? null : targets.get(n.link());
        if (file == null) {
            return;
        }
        previews.preview(file, HitMarks.NONE, false);
        previews.pin(file);
        opened.add(file);
    }

    private Path fileOf(final DefaultMutableTreeNode node) {
        return node.getUserObject() instanceof ViewSpec.TreeNode n ? targets.get(n.link()) : null;
    }

    private Icon icon(final ViewSpec.TreeNode n) {
        return targets.containsKey(n.link()) ? Icons.get("file") : null;
    }

    /** Gold while the file was not opened; the look's own colour after. */
    private Color color(final DefaultMutableTreeNode node) {
        final Path file = fileOf(node);
        return file != null && !opened.contains(file) ? Theme.tint().body : null;
    }

    /** The folder tabs' mark of a new file, while the file was not opened. */
    private Icon mark(final DefaultMutableTreeNode node) {
        final Path file = fileOf(node);
        return file != null && !opened.contains(file) ? Icons.get("new-file") : null;
    }

    private String tip(final DefaultMutableTreeNode node) {
        final Path file = fileOf(node);
        return file == null ? null : Tooltips.wrapped(HomeDir.shorten(file.toString()));
    }

    /** The session with the keyboard, as the window's state says it. */
    public interface Focus {

        /** The session of the panel with the keyboard; empty when that panel is no session's. On the EDT. */
        String session();

        /** Calls {@code change}, on any thread, after the window's state changed; the returned action stops it. */
        Runnable listen(Runnable change);
    }
}
