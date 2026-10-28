package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [FilesTab] in the sidebar's look, painted headless in three tints, so without the look and feel. The window's own
/// picture is `TreeClicksUiTest`'s, `app/build/snapshots/sidebar.png`.
/// - a group is one row in capitals holding its folders; a hairline, no words, parts one group's row from the next
/// - the ground, the selected row's bar and the hairline follow the tint chosen
@ExtendWith(OnEdt.class)
class SidebarLookTest {

    private static final int WIDTH = 280;
    private static final int HEIGHT = 300;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
    }

    private static List<FolderRoot> folders() {
        return List.of(
                new FolderRoot("Scratchpad", "/b/s", "", List.of(), "scratchpad", true, "INDEX.md", "Brain"),
                new FolderRoot("KB", "/b/kb", "", List.of(), "knowledge", false, "", "Brain"),
                new FolderRoot("Memory", "/b/m", "", List.of(), "memory", false, "", "Brain"),
                new FolderRoot("Kit", "/k", "", List.of(), "folder", false, "", "Kit"),
                new FolderRoot("Notes", "/n", "", List.of()));
    }

    private static FilesTab tab() {
        FilesTab tab = new FilesTab(i -> { }, p -> { }, p -> { }, (parent, text, title, options) -> 1);
        tab.render(AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(), List.of(), folders()));
        tab.show(folders().getFirst(), new ViewSpec.TreeNode("Scratchpad", "", List.of(
                new ViewSpec.TreeNode("2026-10-27", "3 entries", List.of(new ViewSpec.TreeNode("a.md", "", "comando-file:/b/s/2026-10-27/a.md#0", List.of()))),
                new ViewSpec.TreeNode("INDEX.md", "", "comando-file:/b/s/INDEX.md#0", List.of()))));
        tab.showRepos(List.of(new RepoStatus("/k", "master", List.of(new RepoStatus.FileChange("CLAUDE.md", "", RepoStatus.Kind.MODIFIED)), false, "")));
        JTree tree = tab.tree().tree();
        tree.expandRow(0);
        tree.setSelectionRow(row(tree, "KB"));
        return tab;
    }

    private static int row(JTree tree, String label) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            if (((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && n.label().equals(label)) {
                return i;
            }
        }
        throw new AssertionError("no row " + label);
    }

    /// The Brain group is one BRAIN row holding its three folders; the KIT and Notes rows each start after a separator, a
    /// header with no words that takes extra height above the row; in each of three tints the ground is the theme's, the
    /// selected row's bar is in the tint and the hairline is painted in its shade over the ground.
    @Test
    void aGroupIsOneRowAndAHairlinePartsTheGroupsInTheTintChosen() {
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        assertThat(List.of("BRAIN", "Scratchpad", "KB", "Memory", "KIT", "Notes"))
                .extracting(l -> tab.header((DefaultMutableTreeNode) tree.getPathForRow(row(tree, l)).getLastPathComponent()))
                .containsExactly(null, null, null, null, "", "");
        assertThat(tree.getRowBounds(row(tree, "KIT")).height).as("the separator's space above its row")
                .isGreaterThan(tree.getRowBounds(row(tree, "KB")).height);

        Tint[] tints = {Tint.TREASURE, Tint.SILVER, Tint.ELECTRIC_BLUE};
        for (int i = 0; i < tints.length; i++) {
            Theme.tint(tints[i]);
            BufferedImage img = paint(tab.component());
            assertThat(img.getRGB(WIDTH - 2, HEIGHT - 2) & 0xFFFFFF).as("the ground").isEqualTo(Theme.ground().getRGB() & 0xFFFFFF);
            int kb = tree.getRowBounds(row(tree, "KB")).y + 4;
            assertThat(near(new Color(img.getRGB(1, kb)), tints[i].at(0.3f), 60)).as("the selected row's bar in " + tints[i]).isTrue();
            assertThat(count(img, Tint.over(Theme.ground(), tints[i].shade, 0.45f), 6)).as("the hairline in " + tints[i]).isGreaterThan(WIDTH);
        }
    }

    /// The first row sits as far below the top as a row sits above the hairline under it: a separator's space, unseen.
    @Test
    void theFirstRowIsAsFarFromTheTopAsFromTheHairlineUnderIt() {
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        BufferedImage img = paint(tab.component());
        int x = WIDTH / 2;
        int top = SwingUtilities.convertPoint(tree, 0, 0, tab.component()).y;
        int line = -1;
        for (int y = top; y < HEIGHT; y++) {
            if (near(new Color(img.getRGB(x, y)), Tint.over(Theme.ground(), Theme.tint().shade, 0.45f), 6)) {
                line = y - top;
                break;
            }
        }
        assertThat(line).as("a hairline").isPositive();
        int above = line - tree.getRowBounds(row(tree, "KIT")).y; // from the bottom of the row above it
        assertThat(tree.getRowBounds(0).y).as("the first row's space above").isEqualTo(above).isPositive();
    }

    private static BufferedImage paint(Component c) {
        c.setSize(WIDTH, HEIGHT);
        layout(c);
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        return img;
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) {
                layout(child);
            }
        }
    }

    private static boolean near(Color a, Color b, int tolerance) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue()) <= tolerance;
    }

    private static int count(BufferedImage img, Color c, int tolerance) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (near(new Color(img.getRGB(x, y)), c, tolerance)) {
                    n++;
                }
            }
        }
        return n;
    }
}
