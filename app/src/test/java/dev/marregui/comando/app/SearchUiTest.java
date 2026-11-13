package dev.marregui.comando.app;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.awaitility.Awaitility;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// Find in files in the real window (records 139 and 141): the Search view in the side pane and the workspace's preview
/// tab a file's hit is read in, with the search behind them. Opens windows, so this runs in `./gradlew :app:uiTest`.
/// - ⌘⇧F opens the view with the keyboard in its field; typing lists a hit under its folder, file and heading
/// - ↓ moves to the hit, which the preview tab shows in italics, every hit marked, the keyboard kept in the rows; F7
///   walks the hits; no window opens for it
/// - ⏎ keeps the preview tab, no longer in italics, and the next file's preview leaves it be
/// - ⌘⇧F in the view's field closes the view
/// - the selection fills the field, else the last search stays across the view closed and opened
/// - the toggles decide what is searched; the Find menu and the palette hold the command
/// - a conversation's hit reads in its row's tooltip; ⏎ opens it in the session holding it with the transcript's Find
///   on the query, else offers Import
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class SearchUiTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String HELD = "11111111-1111-4111-8111-111111111111";
    private static final String STORED = "22222222-2222-4222-8222-222222222222";
    /// Where "budget" starts on line 3 of the plan, and on line 5.
    private static final int PLAN_LINE_3 = "# Plan\n\nthe quarterly ".length();
    private static final int PLAN_LINE_5 = "# Plan\n\nthe quarterly budget stays\n## Later\n".length();

    @TempDir
    Path home;

    private App app;
    private StateLoop loop;
    private Path open;

    /// A window on a folder of the Kit tab, one of its files open in a panel with the keyboard in its text, and two
    /// stored conversations.
    @BeforeEach
    void openWindow() throws Exception {
        final Path notes = Files.createDirectories(home.resolve("notes"));
        Files.writeString(notes.resolve("plan.md"), "# Plan\n\nthe quarterly budget stays\n## Later\nbudget again\n");
        Files.writeString(Files.createDirectories(notes.resolve("docs/old")).resolve("other.md"), "another line\n");
        open = notes.resolve("open.txt");
        Files.writeString(open, "first\nmosaic\n");
        final Path project = Files.createDirectories(home.resolve("projects/-work"));
        Files.writeString(project.resolve(HELD + ".jsonl"), message("what about the marzipan order"));
        Files.writeString(project.resolve(STORED + ".jsonl"), message("what about the nougat order"));
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("folders.json"), "[{\"name\": \"Notes\", \"path\": \"" + notes + "\"}]");
        app = new App(new AppConfig(data, home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "search", () -> {
        });
        app.start();
        loop = app.loop();
        Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null
                && onEdt(() -> frame() != null));
        loop.dispatch(new Intent.OpenFile("", open.toString(), 0));
        Awaitility.await("its panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("open.txt"));
        final RSyntaxTextArea editor = editor();
        Awaitility.await("the keyboard in the text").atMost(WAIT).until(() -> onEdt(() -> {
            editor.requestFocusInWindow();
            return frame().getMostRecentFocusOwner() == editor;
        }));
    }

    /// The window goes with its test.
    @AfterEach
    void closeApp() {
        app.close();
    }

    private static String message(final String text) {
        return "{\"type\":\"user\",\"timestamp\":\"2026-11-07T10:00:00Z\",\"message\":{\"role\":\"user\","
                + "\"content\":\"" + text + "\"}}\n";
    }

    /// The whole gesture on a file: ⌘⇧F, typing, the hit under its folder, file and heading, ↓ to it: the preview tab
    /// shows the file in italics with both hits marked, the first current, while the rows keep the keyboard; F7 moves
    /// to the next hit, in the rows and in the tab; no window opens.
    @Test
    void aHitShowsInThePreviewTabWhileTheRowsKeepTheKeyboard() throws Exception {
        final JTextField field = openViewByKey();
        type(field, "BUDGET");
        Awaitility.await("the count").atMost(WAIT).until(() -> onEdt(() -> foot().equals("2 hits in 1 source")));
        final JTree hits = hits();
        assertThat(onEdt(() -> rows(hits))).containsExactly(
                "Notes · 2 hits in 1 file",
                "plan.md · 2 hits",
                "the quarterly budget stays · Plan · line 3",
                "budget again · Plan › Later · line 5");

        press(field, KeyEvent.VK_DOWN, 0);

        assertThat(onEdt(() -> selectedRow(hits))).startsWith("the quarterly budget stays");
        final RSyntaxTextArea read = preview("# Plan");
        Awaitility.await("both hits marked, the first current").atMost(WAIT).until(() -> onEdt(()
                -> marks(read) == 2 && read.getCaretPosition() == PLAN_LINE_3));
        assertThat(previewPanel("plan.md")).as("the preview tab").isPresent();
        assertThat(onEdt(() -> italic(read))).as("the preview tab's title in italics").isTrue();
        assertThat(onEdt(() -> frame().getMostRecentFocusOwner())).as("the keyboard stays in the rows").isSameAs(hits);

        press(hits, KeyEvent.VK_F7, 0);

        Awaitility.await("F7 moves to the next hit").atMost(WAIT).until(() -> onEdt(()
                -> selectedRow(hits).startsWith("budget again") && read.getCaretPosition() == PLAN_LINE_5));
        assertThat(onEdt(() -> frame().getMostRecentFocusOwner())).as("the keyboard still in the rows").isSameAs(hits);
        assertThat(onEdt(SearchUiTest::dialogs)).as("no window for a hit").isEmpty();
    }

    /// ⏎ on a file's hit keeps its preview tab: no longer in italics, the keyboard in its text; the next file's hit
    /// gets a preview tab of its own and the kept tab stays.
    @Test
    void openKeepsThePreviewTab() throws Exception {
        final JTextField field = openViewByKey();
        type(field, "budget");
        Awaitility.await("the hits").atMost(WAIT).until(() -> onEdt(() -> foot().equals("2 hits in 1 source")));
        press(field, KeyEvent.VK_DOWN, 0);
        final RSyntaxTextArea read = preview("# Plan");
        final JTree hits = hits();

        press(hits, KeyEvent.VK_ENTER, 0);

        Awaitility.await("the tab kept").atMost(WAIT).until(() -> panel("plan.md").map(p -> !p.preview())
                .orElse(false));
        Awaitility.await("not in italics").atMost(WAIT).until(() -> onEdt(() -> !italic(read)));
        Awaitility.await("the keyboard in its text").atMost(WAIT).until(() -> onEdt(()
                -> frame().getMostRecentFocusOwner() == read));

        final JTextField again = openViewByKey();
        onEdt(() -> {
            again.setText("");
            return null;
        });
        type(again, "another");
        Awaitility.await("the other file's hit").atMost(WAIT).until(() -> onEdt(()
                -> foot().equals("1 hit in 1 source")));
        press(again, KeyEvent.VK_DOWN, 0);

        Awaitility.await("its own preview tab").atMost(WAIT).until(() -> previewPanel("other.md").isPresent());
        assertThat(panel("plan.md").map(PanelState::preview)).as("the kept tab stays").contains(false);
        assertThat(onEdt(SearchUiTest::dialogs)).as("no window for a hit").isEmpty();
    }

    /// ⌘⇧F while the view's field has the keyboard closes the view; the view opened again shows the last search.
    @Test
    void theKeyClosesTheViewItIsInAndTheSearchStays() throws Exception {
        final JTextField field = openViewByKey();
        type(field, "budget");
        Awaitility.await("the hits").atMost(WAIT).until(() -> onEdt(() -> foot().equals("2 hits in 1 source")));

        press(field, KeyEvent.VK_F, Platform.menuShortcutMask() | KeyEvent.SHIFT_DOWN_MASK);
        Awaitility.await("the view closed").atMost(WAIT).until(() -> loop.state().views().stream()
                .noneMatch(v -> v.spec() instanceof ViewSpec.Search));

        final JTextField again = openViewByKey();
        assertThat(textOf(again)).isEqualTo("budget");
        Awaitility.await("searched again").atMost(WAIT).until(() -> onEdt(() -> foot().equals("2 hits in 1 source")));
    }

    /// With the Search view's tab behind the Changes view, ⌘⇧F brings it forward and its field takes the keyboard;
    /// ⌘⇧F from the Changes view does not close the Search view.
    @Test
    void theKeyBringsAViewBehindAnotherForward() throws Exception {
        openViewByKey();
        press(onEdt(() -> frame().getMostRecentFocusOwner()), KeyEvent.VK_K, Platform.menuShortcutMask());
        Awaitility.await("the Changes view in front").atMost(WAIT).until(() -> loop.state().views().stream()
                .anyMatch(v -> v.spec() instanceof ViewSpec.Changes));
        Awaitility.await("the Search field hidden").atMost(WAIT).until(() -> onEdt(() -> find(frame(),
                JTextField.class, f -> f.isShowing()
                        && ViewSpec.Search.TITLE.equals(f.getAccessibleContext().getAccessibleName())) == null));

        openViewByKey();

        assertThat(loop.state().views().stream().filter(v -> v.spec() instanceof ViewSpec.Search).count())
                .isEqualTo(1L);
    }

    /// ⏎ typed before the search answers waits for it: the hit selected is the new search's first, not the last one's.
    @Test
    void enterBeforeTheAnswerSelectsTheNewSearchsFirstHit() throws Exception {
        final JTextField field = openViewByKey();
        type(field, "budget");
        Awaitility.await("the hits").atMost(WAIT).until(() -> onEdt(() -> foot().equals("2 hits in 1 source")));
        onEdt(() -> {
            field.setText("");
            return null;
        });
        type(field, "mosaic");

        press(field, KeyEvent.VK_ENTER, 0);

        final JTree hits = hits();
        Awaitility.await("the new search's hit selected").atMost(WAIT).until(() -> onEdt(()
                -> selectedRow(hits).startsWith("mosaic")));
    }

    /// The text selected fills the field and is searched at once.
    @Test
    void theSelectionFillsTheField() throws Exception {
        final RSyntaxTextArea editor = editor();
        onEdt(() -> {
            final int at = editor.getText().indexOf("mosaic");
            editor.select(at, at + "mosaic".length());
            return null;
        });

        final JTextField field = openViewByKey();

        assertThat(textOf(field)).isEqualTo("mosaic");
        Awaitility.await("searched at once").atMost(WAIT).until(() -> onEdt(() -> foot().equals("1 hit in 1 source")));
    }

    /// Files off leaves the folder's hits out; both off searches nothing and says so.
    @Test
    void theTogglesDecideWhatIsSearched() throws Exception {
        final JTextField field = openViewByKey();
        type(field, "budget");
        Awaitility.await("the hits").atMost(WAIT).until(() -> onEdt(() -> foot().equals("2 hits in 1 source")));
        final JToggleButton files = onEdt(() -> find(view(field), JToggleButton.class,
                b -> "Files".equals(b.getAccessibleContext().getAccessibleName())));
        final JToggleButton conversations = onEdt(() -> find(view(field), JToggleButton.class,
                b -> "Conversations".equals(b.getAccessibleContext().getAccessibleName())));
        assertThat(onEdt(() -> files.getIcon() != null && files.getText().isEmpty() && files.getToolTipText() != null))
                .as("an icon with a tooltip").isTrue();

        onEdt(() -> {
            files.doClick();
            return null;
        });
        Awaitility.await("no hit").atMost(WAIT).until(() -> onEdt(() -> foot().equals("no matches")));
        onEdt(() -> {
            conversations.doClick();
            return null;
        });

        Awaitility.await("nothing to search").atMost(WAIT).until(() -> onEdt(()
                -> foot().startsWith("Nothing to search")));
    }

    /// The Find menu lists the command after the view's own Find, then the search's hit steps; the palette offers it
    /// by its name.
    @Test
    void theFindMenuAndThePaletteHoldTheCommand() throws Exception {
        final List<String> items = onEdt(() -> Stream.of(frame().getJMenuBar().getMenu(1).getMenuComponents())
                .filter(JMenuItem.class::isInstance).map(c -> ((JMenuItem) c).getText()).toList());

        assertThat(onEdt(() -> frame().getJMenuBar().getMenu(1).getText())).isEqualTo("Find");
        assertThat(items).containsExactly("Find…", "Find next", "Find previous", "Find in files…", "Next hit",
                "Previous hit");

        press(onEdt(() -> frame().getMostRecentFocusOwner()), KeyEvent.VK_P,
                Platform.menuShortcutMask() | KeyEvent.SHIFT_DOWN_MASK);
        final AtomicReference<JDialog> palette = new AtomicReference<>();
        Awaitility.await("the palette").atMost(WAIT).until(() -> {
            palette.set(onEdt(() -> owned().stream().filter(w -> w instanceof JDialog d && d.isVisible()
                    && d.isUndecorated())
                    .map(JDialog.class::cast).findFirst().orElse(null)));
            return palette.get() != null;
        });
        final JTextField typed = onEdt(() -> find(palette.get(), JTextField.class, f -> true));
        type(typed, "find in files");
        press(typed, KeyEvent.VK_ENTER, 0);

        Awaitility.await("the Search view").atMost(WAIT).until(() -> loop.state().views().stream()
                .anyMatch(v -> v.spec() instanceof ViewSpec.Search));
    }

    /// A conversation a session holds lists under Conversations, named by who wrote it; ⏎ on its hit opens that
    /// session's panel with the transcript's Find on the query.
    @Test
    void aConversationASessionHoldsOpensThereWithTheFind() throws Exception {
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("helper", home.toString()).resumable(HELD)));
        Awaitility.await("the session's panel").atMost(WAIT).until(() -> loop.state().panels().stream().anyMatch(p
                -> p.session().equals("helper")));
        loop.dispatch(new Intent.OpenFile("", open.toString(), 0));
        Awaitility.await("the document in front").atMost(WAIT).until(()
                -> loop.state().focusedPanel().title().equals("open.txt"));
        final JTextField field = openViewByKey();
        type(field, "marzipan");
        Awaitility.await("the hit").atMost(WAIT).until(() -> onEdt(() -> foot().equals("1 hit in 1 source")));
        final JTree hits = hits();
        assertThat(onEdt(() -> rows(hits)).getFirst()).startsWith("Conversations");
        assertThat(onEdt(() -> rows(hits)).getLast()).contains("You · ");

        press(field, KeyEvent.VK_DOWN, 0);
        press(hits, KeyEvent.VK_ENTER, 0);

        Awaitility.await("the session in front").atMost(WAIT).until(()
                -> loop.state().focusedPanel().session().equals("helper"));
        final AtomicReference<JTextField> bar = new AtomicReference<>();
        Awaitility.await("the transcript's Find").atMost(WAIT).until(() -> {
            bar.set(onEdt(() -> find(frame(), JTextField.class,
                    f -> f.isShowing() && "Find".equals(f.getAccessibleContext().getAccessibleName()))));
            return bar.get() != null;
        });
        assertThat(textOf(bar.get())).isEqualTo("marzipan");
    }

    /// A conversation no session holds reads in its row's tooltip, with no preview tab; ⏎ on it offers the Import
    /// dialog.
    @Test
    void aStoredConversationReadsInItsTooltipAndOffersImport() throws Exception {
        final JTextField field = openViewByKey();
        type(field, "nougat");
        Awaitility.await("the hit").atMost(WAIT).until(() -> onEdt(() -> foot().equals("1 hit in 1 source")));
        final JTree hits = hits();

        press(field, KeyEvent.VK_DOWN, 0);

        Awaitility.await("the hit selected").atMost(WAIT).until(() -> onEdt(() -> selectedRow(hits).contains("You")));
        assertThat(onEdt(() -> tipOfSelected(hits))).contains("what about the nougat order");
        assertThat(loop.state().panels().stream().noneMatch(PanelState::preview)).as("no preview tab").isTrue();

        press(hits, KeyEvent.VK_ENTER, 0);

        Awaitility.await("the Import dialog").atMost(WAIT).until(() -> onEdt(
                () -> owned().stream().anyMatch(w -> w instanceof JDialog d && d.isVisible()
                        && d.getTitle().startsWith("Import"))));
    }

    /// Presses ⌘⇧F where the main window's keyboard is, and returns the view's field once it has the keyboard.
    private JTextField openViewByKey() throws Exception {
        press(onEdt(() -> frame().getMostRecentFocusOwner()), KeyEvent.VK_F,
                Platform.menuShortcutMask() | KeyEvent.SHIFT_DOWN_MASK);
        final AtomicReference<JTextField> out = new AtomicReference<>();
        Awaitility.await("the Search view's field with the keyboard").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), JTextField.class, f -> f.isShowing()
                    && ViewSpec.Search.TITLE.equals(f.getAccessibleContext().getAccessibleName()))));
            return out.get() != null && onEdt(() -> frame().getMostRecentFocusOwner() == out.get());
        });
        return out.get();
    }

    /// The text of the preview tab that shows the file starting with `start`, once it shows.
    private static RSyntaxTextArea preview(final String start) {
        final AtomicReference<RSyntaxTextArea> out = new AtomicReference<>();
        Awaitility.await("the preview tab's text").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), RSyntaxTextArea.class,
                    a -> a.isShowing() && a.getText().startsWith(start))));
            return out.get() != null;
        });
        return out.get();
    }

    private Optional<PanelState> panel(final String title) {
        return loop.state().panels().stream().filter(p -> p.title().equals(title)).findFirst();
    }

    private Optional<PanelState> previewPanel(final String title) {
        return panel(title).filter(PanelState::preview);
    }

    /// The search's marks in `area`: the highlights in the find colours.
    private static long marks(final RSyntaxTextArea area) {
        return Stream.of(area.getHighlighter().getHighlights()).map(Highlighter.Highlight::getPainter)
                .filter(p -> p instanceof DefaultHighlighter.DefaultHighlightPainter d
                        && (d.getColor() == Theme.FIND_MATCH || d.getColor() == Theme.FIND_CURRENT))
                .count();
    }

    /// Whether the tab holding `c` shows its title in italics, as the preview tab's is.
    private static boolean italic(final Component c) {
        for (Component at = c; at != null; at = at.getParent()) {
            if (at.getParent() instanceof JTabbedPane strip && strip.indexOfComponent(at) >= 0) {
                return strip.getTabComponentAt(strip.indexOfComponent(at)) instanceof JLabel l
                        && l.getFont().isItalic();
            }
        }
        return false;
    }

    /// The tooltip of the selected row, as the pointer over it shows it.
    private static String tipOfSelected(final JTree tree) {
        final Rectangle r = tree.getPathBounds(tree.getSelectionPath());
        return tree.getToolTipText(new MouseEvent(tree, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                r.x + r.width / 2, r.y + r.height / 2, 0, false));
    }

    /// The dialogs the main window shows.
    private static List<Window> dialogs() {
        return owned().stream().filter(w -> w instanceof JDialog && w.isVisible()).toList();
    }

    private static JTree hits() throws Exception {
        return onEdt(() -> find(frame(), JTree.class, t -> t.isShowing()
                && "Hits".equals(t.getAccessibleContext().getAccessibleName())));
    }

    /// The rows the tree shows, each its label and, after " · ", its detail.
    private static List<String> rows(final JTree tree) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            out.add(row(tree.getPathForRow(i)));
        }
        return out;
    }

    private static String selectedRow(final JTree tree) {
        return tree.getSelectionPath() == null ? "" : row(tree.getSelectionPath());
    }

    private static String row(final TreePath p) {
        if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n) {
            return n.detail().isEmpty() ? n.label() : n.label() + " · " + n.detail();
        }
        return "";
    }

    /// The line at the foot of the Search view.
    private static String foot() throws Exception {
        final Container view = view(find(frame(), JTextField.class, f -> f.isShowing()
                && ViewSpec.Search.TITLE.equals(f.getAccessibleContext().getAccessibleName())));
        return view == null ? "" : ((JLabel) ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.SOUTH))
                .getText();
    }

    /// The Search view holding `field`: the container with its line at the foot.
    private static Container view(final JTextField field) {
        Container view = field.getParent();
        while (view != null && !(view.getLayout() instanceof BorderLayout b
                && b.getLayoutComponent(BorderLayout.SOUTH) instanceof JLabel)) {
            view = view.getParent();
        }
        return view;
    }

    private static List<Window> owned() {
        return new ArrayList<>(List.of(frame().getOwnedWindows()));
    }

    private static String textOf(final JTextField field) throws Exception {
        return onEdt(field::getText);
    }

    private RSyntaxTextArea editor() {
        final AtomicReference<RSyntaxTextArea> out = new AtomicReference<>();
        Awaitility.await("the editor").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), RSyntaxTextArea.class,
                    a -> a.isShowing() && a.getText().contains("mosaic"))));
            return out.get() != null;
        });
        return out.get();
    }

    /// Types `text` into `field` key by key, as a typist would.
    private static void type(final JTextField field, final String text) throws Exception {
        onEdt(() -> {
            for (final char c : text.toCharArray()) {
                field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                        KeyEvent.VK_UNDEFINED, c));
            }
            return null;
        });
    }

    private static void press(final Component target, final int code, final int modifiers) throws Exception {
        onEdt(() -> {
            final long now = System.currentTimeMillis();
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, code,
                    KeyEvent.CHAR_UNDEFINED));
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, code,
                    KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    private static JFrame frame() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(
            final Component c,
            final Class<? super T> type,
            final Predicate<T> which
    ) {
        if (type.isInstance(c) && which.test((T) c)) {
            return (T) c;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final T found = find(child, type, which);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }
}
