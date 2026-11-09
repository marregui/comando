package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/// [Workspace] with its right-hand partition, a [SidePane]: one strip in the main tabs' look holding the side panels,
/// then the side views.
/// - a side panel renders in the partition, before its views, and moves back as the same component
/// - `focused()` is the state's focused panel in either strip; the partition shows it when the focus moves there
/// - ⌘n counts the main tabs, then the partition's panels
/// - the tab menus move a panel or a view to the other strip by an intent
/// - the preview tab's title is in italics, a double click pins it, and it is not reopened after a close
/// - `whenShown` runs with a panel by its id, now or at the render that shows it, the latest ask winning
class WorkspacePartitionTest {

    private static final PanelId A = new PanelId("a");
    private static final PanelId B = new PanelId("b");
    private static final PanelId C = new PanelId("c");
    private static final PanelId D = new PanelId("d");

    private static PanelState panel(final PanelId id, final boolean side) {
        return new PanelState(id, id.value(), "", "").withSide(side);
    }

    private static ViewState sideView(final long id) {
        return new ViewState(id, "View " + id, new ViewSpec.Markdown("# " + id), Placement.SIDE, false, "", 1L);
    }

    private static List<Component> components(final JTabbedPane tabs) {
        final List<Component> out = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            out.add(tabs.getComponentAt(i));
        }
        return out;
    }

    private static JMenuItem item(final JPopupMenu menu, final String text) {
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m && m.getText().equals(text)) {
                return m;
            }
        }
        throw new AssertionError("no menu item [text=" + text + ']');
    }

    private static List<String> items(final JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    /// What `read` returns, read on the EDT; its failure is thrown here.
    private static <T> T onEdt(final Callable<T> read) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(read.call());
            } catch (final Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }

    /// A side panel sits in the partition before its side view and the partition shows; placed back in the main tabs,
    /// the same component sits there at its place and the partition, holding only the view, still shows; without the
    /// view it hides.
    @Test
    void aSidePanelRendersInThePartitionBeforeItsViewsAndMovesBackAsTheSameComponent() throws Exception {
        final Fixture f = Fixture.of(Map.of());
        final AppState s = AppState.initial(List.of(panel(A, false), panel(B, true)), List.of(), List.of())
                .withViews(List.of(sideView(7)));
        assertThat(f.render(s)).isTrue();
        final EditorPanel a = onEdt(() -> f.workspace.panel(A));
        final EditorPanel b = onEdt(() -> f.workspace.panel(B));
        assertThat(onEdt(() -> components(f.main()))).containsExactly(a);
        assertThat(onEdt(() -> f.strip().getTabCount())).isEqualTo(2);
        assertThat(onEdt(() -> f.strip().getComponentAt(0))).isSameAs(b);
        assertThat(onEdt(f.side::hasPanels)).isTrue();
        assertThat(onEdt(f.side::hasViews)).isTrue();

        final AppState back = s.withPanels(List.of(panel(A, false), panel(B, false)), A);
        assertThat(f.render(back)).as("the view keeps the partition shown").isTrue();
        assertThat(onEdt(() -> components(f.main()))).containsExactly(a, b);
        assertThat(onEdt(() -> f.workspace.panel(B))).isSameAs(b);
        assertThat(onEdt(f.side::hasPanels)).isFalse();
        assertThat(onEdt(() -> f.strip().getTabCount())).isEqualTo(1);

        assertThat(f.render(back.withViews(List.of()))).as("neither a panel nor a view: hidden").isFalse();
        assertThat(f.render(s.withViews(List.of()))).as("a side panel alone shows it").isTrue();
        assertThat(onEdt(() -> f.strip().getComponentAt(0))).isSameAs(b);
    }

    /// `focused()` is the state's focused panel, a side one included. The focus moving to a side panel brings its tab
    /// forward over a view; a later render with the focus unchanged leaves a view the user brought forward in front.
    /// The main tabs keep their own selection meanwhile.
    @Test
    void focusedIsTheStatesPanelInEitherStripAndThePartitionShowsItWhenTheFocusMoves() throws Exception {
        final Fixture f = Fixture.of(Map.of());
        final AppState s = AppState.initial(List.of(panel(A, false), panel(B, true)), List.of(), List.of())
                .withViews(List.of(sideView(7)));
        f.render(s);
        final EditorPanel a = onEdt(() -> f.workspace.panel(A));
        final EditorPanel b = onEdt(() -> f.workspace.panel(B));
        assertThat(onEdt(f.workspace::focused)).isSameAs(a);
        assertThat(onEdt(() -> f.strip().getSelectedIndex())).as("the new view comes forward").isEqualTo(1);

        final AppState onB = s.withFocused(B);
        f.render(onB);
        assertThat(onEdt(f.workspace::focused)).isSameAs(b);
        assertThat(onEdt(() -> f.strip().getSelectedComponent())).isSameAs(b);
        assertThat(onEdt(() -> f.main().getSelectedComponent())).isSameAs(a);

        SwingUtilities.invokeAndWait(() -> f.side.front(7));
        f.render(onB);
        assertThat(onEdt(() -> f.strip().getSelectedIndex())).as("the focus did not move: the view stays in front")
                .isEqualTo(1);
        assertThat(onEdt(f.workspace::focused)).isSameAs(b);

        f.render(onB.withFocused(A));
        assertThat(onEdt(f.workspace::focused)).isSameAs(a);
    }

    /// ⌘n goes to the main tabs left to right, then the partition's panels, whatever their order among all panels;
    /// views take no number, and nothing lies past the last panel or before the first.
    @Test
    void numberedCountsTheMainTabsThenThePartitionsPanels() throws Exception {
        final Fixture f = Fixture.of(Map.of());
        f.render(AppState.initial(List.of(panel(A, true), panel(B, false), panel(C, true), panel(D, false)), List.of(),
                List.of()).withViews(List.of(sideView(7))));
        final EditorPanel[] at = new EditorPanel[6];
        SwingUtilities.invokeAndWait(() -> {
            for (int n = 0; n < at.length; n++) {
                at[n] = f.workspace.numbered(n);
            }
        });
        assertThat(at[0]).isNull();
        assertThat(at[1]).isSameAs(onEdt(() -> f.workspace.panel(B)));
        assertThat(at[2]).isSameAs(onEdt(() -> f.workspace.panel(D)));
        assertThat(at[3]).isSameAs(onEdt(() -> f.workspace.panel(A)));
        assertThat(at[4]).isSameAs(onEdt(() -> f.workspace.panel(C)));
        assertThat(at[5]).isNull();
    }

    /// A main tab's menu keeps its close items and adds "Move to the right side", which sends `PlacePanel` to the side,
    /// disabled for the last panel of the main tabs; a side panel's menu sends it back with "Move to the main tabs".
    /// Each move item has its icon.
    @Test
    void aPanelsTabMenuMovesItToTheOtherStrip() throws Exception {
        final Fixture f = Fixture.of(Map.of());
        f.render(AppState.initial(List.of(panel(A, false), panel(B, false), panel(C, true)), List.of(), List.of()));
        final List<String> labels = new ArrayList<>();
        final boolean[] icons = new boolean[2];
        SwingUtilities.invokeAndWait(() -> {
            final JPopupMenu main = f.workspace.tabMenu(0);
            labels.addAll(items(main));
            icons[0] = item(main, "Move to the right side").getIcon() != null;
            item(main, "Move to the right side").doClick();
            final JPopupMenu side = f.workspace.tabMenu(f.strip(), 0);
            icons[1] = item(side, "Move to the main tabs").getIcon() != null;
            item(side, "Move to the main tabs").doClick();
        });
        assertThat(labels).containsExactly("Close", "Close others", "Close to the right", "Close to the left",
                "Move to the right side");
        assertThat(icons).containsExactly(true, true);
        assertThat(f.intents).filteredOn(i -> i instanceof Intent.PlacePanel)
                .containsExactly(new Intent.PlacePanel(A, true), new Intent.PlacePanel(C, false));

        f.render(AppState.initial(List.of(panel(A, false), panel(C, true)), List.of(), List.of()));
        final boolean[] enabled = new boolean[1];
        SwingUtilities.invokeAndWait(
                () -> enabled[0] = item(f.workspace.tabMenu(0), "Move to the right side").isEnabled());
        assertThat(enabled[0]).as("the main tabs keep one panel").isFalse();
    }

    /// A view's tab menu, in the partition and in the lower panel, sends `PlaceView` to the other placement:
    /// "Move to the lower panel" from the side, "Move to the right side" from below, each with its icon.
    @Test
    void aViewsTabMenuMovesItToTheOtherPlacement() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<String> labels = new CopyOnWriteArrayList<>();
        final List<Boolean> icons = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            final WidgetRegistry registry = new WidgetRegistry();
            registry.register("markdown", ViewSpec.Markdown.class, _ -> new Label());
            final WidgetContext context = new WidgetContext(intents::add, new ActionRegistry());
            final ViewTabs side = new ViewTabs(new JTabbedPane(), 0, Placement.SIDE, registry, context);
            final ViewTabs lower = new ViewTabs(new JTabbedPane(), 4, Placement.LOWER, registry, context);
            final List<JPopupMenu> menus =
                    List.of(side.menu(sideView(7)), lower.menu(sideView(8).placed(Placement.LOWER)));
            for (final JPopupMenu m : menus) {
                for (final Component c : m.getComponents()) {
                    if (c instanceof JMenuItem item) {
                        labels.add(item.getText());
                        icons.add(item.getIcon() != null);
                        item.doClick();
                    }
                }
            }
        });
        assertThat(labels).containsExactly("Move to the lower panel", "Move to the right side");
        assertThat(icons).containsExactly(true, true);
        assertThat(intents).containsExactly(
                new Intent.PlaceView(7, Placement.LOWER),
                new Intent.PlaceView(8, Placement.SIDE));
    }

    /// The preview tab shows its title in italics, in the main tabs or the partition, and a double click on it sends
    /// `PinPanel`; once pinned its title is upright again. A preview closed is not in the closed list and posts no
    /// notice, unlike a document tab.
    @Test
    void thePreviewTabIsItalicPinnedByADoubleClickAndNotReopened() throws Exception {
        final Fixture f = Fixture.of(Map.of(B, Path.of("/tmp/b.md"), C, Path.of("/tmp/c.md")));
        final PanelState preview = new PanelState(B, "b.md", "", "", true, false, true);
        final PanelState document = new PanelState(C, "c.md", "", "", true);
        final AppState s = AppState.initial(List.of(panel(A, false), preview, document), List.of(), List.of());
        f.render(s);
        final boolean[] seen = new boolean[4];
        SwingUtilities.invokeAndWait(() -> {
            final JTabbedPane main = f.main();
            seen[0] = main.getTabComponentAt(1) instanceof JLabel l
                    && l.getFont().isItalic()
                    && l.getText().equals("b.md");
            seen[1] = main.getTabComponentAt(2) != null;
            main.setSize(600, 200);
            main.doLayout();
            final Rectangle r = main.getBoundsAt(1);
            main.dispatchEvent(new MouseEvent(main, MouseEvent.MOUSE_CLICKED, 0L, InputEvent.BUTTON1_DOWN_MASK,
                    r.x + r.width / 2, r.y + r.height / 2, 2, false, MouseEvent.BUTTON1));
        });
        assertThat(seen[0]).as("the preview's title in italics").isTrue();
        assertThat(seen[1]).as("a tab that stays has the strip's own title").isFalse();
        assertThat(f.intents).filteredOn(i -> i instanceof Intent.PinPanel).containsExactly(new Intent.PinPanel(B));

        f.render(s.withPanels(List.of(panel(A, false), preview.withSide(true), document), A));
        SwingUtilities.invokeAndWait(
                () -> seen[2] = f.strip().getTabComponentAt(0) instanceof JLabel l && l.getFont().isItalic());
        assertThat(seen[2]).as("italic in the partition too").isTrue();

        f.render(s.withPanels(List.of(panel(A, false), preview.withPreview(false), document), A));
        SwingUtilities.invokeAndWait(() -> seen[3] = f.main().getTabComponentAt(1) != null);
        assertThat(seen[3]).as("pinned: upright").isFalse();

        f.render(s.withPanels(List.of(panel(A, false), preview, document), A));
        f.intents.clear();
        f.render(s.withPanels(List.of(panel(A, false), document), A));
        assertThat(onEdt(f.workspace::hasClosed)).as("a preview closed is not reopened").isFalse();
        assertThat(f.intents).noneMatch(Intent.Notify.class::isInstance);
        f.render(s.withPanels(List.of(panel(A, false)), A));
        assertThat(onEdt(f.workspace::popClosed)).map(Workspace.ClosedPanel::id).contains(C);
    }

    /// `whenShown` runs at once with a panel shown, a side one included; for a panel not shown yet it runs once, at
    /// the render that shows it, and only the latest ask for that panel runs.
    @Test
    void whenShownRunsWithThePanelNowOrAtTheRenderThatShowsIt() throws Exception {
        final Path b = Path.of("/tmp/b.md");
        final Path c = Path.of("/tmp/c.md");
        final Fixture f = Fixture.of(Map.of(B, b, C, c));
        final PanelState sideB = new PanelState(B, "b.md", "", "", true).withSide(true);
        final AppState s = AppState.initial(List.of(panel(A, false), sideB), List.of(), List.of());
        f.render(s);
        final List<EditorPanel> ran = new CopyOnWriteArrayList<>();
        final List<EditorPanel> replaced = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            f.workspace.whenShown(B, ran::add);
            f.workspace.whenShown(C, replaced::add);
            f.workspace.whenShown(C, ran::add);
        });
        assertThat(ran).containsExactly(onEdt(() -> f.workspace.panel(B)));

        final PanelState mainC = new PanelState(C, "c.md", "", "", true);
        final AppState withC = s.withPanels(List.of(panel(A, false), sideB, mainC), A);
        f.render(withC);
        f.render(withC);
        assertThat(ran).containsExactly(onEdt(() -> f.workspace.panel(B)), onEdt(() -> f.workspace.panel(C)));
        assertThat(replaced).isEmpty();
    }

    /// The partition's strip has the main tabs' look: card tabs, one scroll button, closable panel tabs with the same
    /// tooltip, scrolling tabs. A view's tab shows no strip close button, its header has its own.
    @Test
    void thePartitionsStripHasTheMainTabsLook() throws Exception {
        final Fixture f = Fixture.of(Map.of());
        f.render(AppState.initial(List.of(panel(A, false)), List.of(), List.of()).withViews(List.of(sideView(7))));
        for (final String key : List.of(
                "JTabbedPane.tabType",
                "JTabbedPane.scrollButtonsPolicy",
                "JTabbedPane.tabClosable",
                "JTabbedPane.tabCloseToolTipText")) {
            assertThat(onEdt(() -> f.strip().getClientProperty(key))).as(key).isNotNull()
                    .isEqualTo(onEdt(() -> f.main().getClientProperty(key)));
        }
        assertThat(onEdt(() -> f.strip().getClientProperty("JTabbedPane.tabType"))).isEqualTo("card");
        assertThat(onEdt(() -> f.strip().getTabLayoutPolicy()))
                .isEqualTo(onEdt(() -> f.main().getTabLayoutPolicy()))
                .isEqualTo(JTabbedPane.SCROLL_TAB_LAYOUT);
        assertThat(onEdt(() -> ((JComponent) f.strip().getComponentAt(0)).getClientProperty(TabLook.CLOSABLE)))
                .isEqualTo(false);
    }

    /** Panel text in memory, each panel's file from a map. */
    private static final class Texts implements PanelTexts {
        private final Map<PanelId, Path> files;

        Texts(final Map<PanelId, Path> files) {
            this.files = files;
        }

        @Override
        public String load(final PanelId panel) {
            return "";
        }

        @Override
        public Optional<Path> file(final PanelId panel) {
            return Optional.ofNullable(files.get(panel));
        }

        @Override
        public boolean document(final PanelId panel) {
            return files.containsKey(panel);
        }

        @Override
        public void changed(final PanelId panel, final String text) {
        }

        @Override
        public void forget(final PanelId panel) {
        }
    }

    /** A widget showing a label, for the side views. */
    private static final class Label implements Widget<ViewSpec.Markdown> {
        private final JLabel label = new JLabel();

        @Override
        public JComponent component() {
            return label;
        }

        @Override
        public void render(final ViewSpec.Markdown view) {
            label.setText(view.markdown());
        }

        @Override
        public void unmount() {
        }
    }

    /** A workspace handing its side panels to a partition, both built on the EDT, recording the intents sent. */
    private static final class Fixture {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        Workspace workspace;
        SidePane side;

        static Fixture of(final Map<PanelId, Path> files) throws Exception {
            final Fixture f = new Fixture();
            SwingUtilities.invokeAndWait(() -> {
                final WidgetRegistry registry = new WidgetRegistry();
                registry.register("markdown", ViewSpec.Markdown.class, _ -> new Label());
                f.side = new SidePane(new WidgetContext(f.intents::add, new ActionRegistry()), registry);
                f.workspace = new Workspace(f.intents::add, new Texts(files), new SchemaRegistry());
                f.workspace.partition(f.side);
            });
            return f;
        }

        JTabbedPane main() {
            return (JTabbedPane) workspace.component();
        }

        JTabbedPane strip() {
            return (JTabbedPane) side.component();
        }

        /** Renders {@code s} as the shell does: the workspace first, then the partition; returns whether it shows. */
        boolean render(final AppState s) throws Exception {
            final boolean[] shows = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                workspace.render(s);
                shows[0] = side.render(s);
            });
            return shows[0];
        }
    }
}
