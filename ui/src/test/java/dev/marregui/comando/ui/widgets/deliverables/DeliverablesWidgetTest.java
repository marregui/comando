package dev.marregui.comando.ui.widgets.deliverables;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.HitMarks;
import dev.marregui.comando.ui.kit.text.Previews;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [DeliverablesWidget]: the Files written view (record 143).
/// - one row per file the session with the keyboard wrote, in order, gold until opened
/// - moving the selection previews the file without the keyboard; Enter keeps the tab and the row turns plain
/// - the rows follow the session as it writes, and the session that takes the keyboard
/// - a panel that is no session's keeps the rows shown
class DeliverablesWidgetTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> focused = new AtomicReference<>("");
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final OpenedFiles opened = OpenedFiles.inMemory();
    private final TranscriptRegistry transcripts = new TranscriptRegistry();
    private DeliverablesWidget widget;

    @BeforeEach
    void mount() throws InterruptedException, InvocationTargetException {
        focused.set("s1");
        SwingUtilities.invokeAndWait(() -> widget = new DeliverablesWidget(new DeliverablesWidget.Focus() {
            @Override
            public String session() {
                return focused.get();
            }

            @Override
            public Runnable listen(final Runnable change) {
                listeners.add(change);
                return () -> listeners.remove(change);
            }
        }, transcripts, opened, new Previews() {
            @Override
            public void preview(final Path file, final HitMarks marks, final boolean takeKeyboard) {
                calls.add("preview " + file + (takeKeyboard ? " keyboard" : ""));
            }

            @Override
            public void pin(final Path file) {
                calls.add("pin " + file);
            }
        }));
    }

    @AfterEach
    void unmount() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(widget::unmount);
    }

    /// Two files written show as two rows in the order written, each gold, the foot counting them.
    @Test
    void theFilesWrittenShowInOrderGoldUntilOpened() {
        final Transcript t = transcripts.of("s1");
        write(t, "/w/b.md");
        write(t, "/w/a.md");

        await().atMost(WAIT).until(() -> rows().equals(List.of("b.md", "a.md")));

        assertThat(colorOf(0)).isEqualTo(Theme.tint().body);
        assertThat(colorOf(1)).isEqualTo(Theme.tint().body);
    }

    /// Selecting a row previews its file without the keyboard; Enter keeps the tab and the row is gold no more.
    @Test
    void selectingPreviewsAndEnterKeepsTheTabAndMarksTheFileOpened() {
        write(transcripts.of("s1"), "/w/b.md");
        await().atMost(WAIT).until(() -> rows().equals(List.of("b.md")));

        onEdt(() -> {
            tree().setSelectionRow(0);
            return null;
        });
        assertThat(calls).containsExactly("preview /w/b.md");

        onEdt(() -> {
            tree().getActionMap().get("activate").actionPerformed(new ActionEvent(tree(), 0, "activate"));
            return null;
        });

        assertThat(calls).containsExactly("preview /w/b.md", "preview /w/b.md", "pin /w/b.md");
        assertThat(onEdt(() -> opened.contains(Path.of("/w/b.md")))).isTrue();
        assertThat(colorOf(0)).isNotEqualTo(Theme.tint().body);
    }

    /// A file opened elsewhere turns its row plain.
    @Test
    void aFileOpenedElsewhereTurnsPlain() {
        write(transcripts.of("s1"), "/w/b.md");
        await().atMost(WAIT).until(() -> rows().equals(List.of("b.md")));

        onEdt(() -> {
            opened.add(Path.of("/w/b.md"));
            return null;
        });

        assertThat(colorOf(0)).isNotEqualTo(Theme.tint().body);
    }

    /// The rows follow the session that takes the keyboard; a panel that is no session's keeps them.
    @Test
    void theRowsFollowTheSessionWithTheKeyboard() {
        write(transcripts.of("s1"), "/w/one.md");
        write(transcripts.of("s2"), "/w/two.md");
        await().atMost(WAIT).until(() -> rows().equals(List.of("one.md")));

        focused.set("s2");
        listeners.forEach(Runnable::run);
        await().atMost(WAIT).until(() -> rows().equals(List.of("two.md")));

        focused.set("");
        listeners.forEach(Runnable::run);
        write(transcripts.of("s2"), "/w/three.md");

        await().atMost(WAIT).until(() -> rows().equals(List.of("two.md", "three.md")));
    }

    private static void write(final Transcript t, final String path) {
        t.append(new Block.ToolCall("toolu_" + t.size(), "Write", "{\"file_path\":\"" + path + "\"}", "",
                Block.ToolStatus.DONE, ""));
        t.changed();
    }

    private static <T> T onEdt(final Supplier<T> read) {
        final AtomicReference<T> out = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> out.set(read.get()));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
        return out.get();
    }

    private static <T extends Component> T find(final Component c, final Class<T> type) {
        if (type.isInstance(c)) {
            return type.cast(c);
        }
        if (c instanceof Container box) {
            for (final Component child : box.getComponents()) {
                final T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JTree tree() {
        return find(widget.component(), JTree.class);
    }

    private List<String> rows() {
        return onEdt(() -> {
            final JTree t = tree();
            final List<String> out = new ArrayList<>();
            for (int i = 0; i < t.getRowCount(); i++) {
                final DefaultMutableTreeNode n = (DefaultMutableTreeNode) t.getPathForRow(i).getLastPathComponent();
                out.add(((ViewSpec.TreeNode) n.getUserObject()).label());
            }
            return out;
        });
    }

    /// The colour row `row` is painted in while not selected.
    private Color colorOf(final int row) {
        return onEdt(() -> {
            final JTree t = tree();
            final Object value = t.getPathForRow(row).getLastPathComponent();
            final Component c = t.getCellRenderer().getTreeCellRendererComponent(t, value, false, false, true, row,
                    false);
            return find(c, JLabel.class).getForeground();
        });
    }
}
