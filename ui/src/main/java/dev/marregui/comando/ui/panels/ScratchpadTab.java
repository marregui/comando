package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.tree.TreeView;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The sidebar's Scratchpad tab: a navigator of the scratchpad's folders and files, without a root row (the tab says
 * where it is). One click opens a file or a folder; each kind of file has its icon. The tree follows the folder as the
 * tools adapter reads it, keeping what the user opened and selected; the newest day is open at the first load.
 */
final class ScratchpadTab {

    private final TreeView tree = new TreeView();
    private boolean loaded;

    ScratchpadTab(Consumer<Path> open) {
        tree.tree().setRootVisible(false);
        tree.singleClick();
        tree.icons(n -> Icons.get(iconName(n)));
        tree.onActivate(n -> file(n, open));
        tree.onMenu((n, e) -> {
            if (n.link().startsWith(SourceLinks.SCHEME)) {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem item = new JMenuItem("Open in a panel");
                item.addActionListener(a -> file(n, open));
                menu.add(item);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        tree.tree().getAccessibleContext().setAccessibleName("Scratchpad");
    }

    JComponent component() {
        return tree.component();
    }

    TreeView tree() {
        return tree;
    }

    /** Shows the scratchpad as read: the first time with the newest day open, then keeping the user's tree. */
    void show(ViewSpec.TreeNode root) {
        if (loaded) {
            tree.update(root);
            return;
        }
        loaded = true;
        tree.setRoot(root, 1);
        int newestDay = -1;
        for (int i = 0; i < tree.tree().getRowCount(); i++) {
            TreePath p = tree.tree().getPathForRow(i);
            if (p.getPathCount() == 2 && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && n.label().matches("\\d{4}-\\d{2}-\\d{2}")) {
                newestDay = i;
            }
        }
        if (newestDay >= 0) {
            tree.tree().expandRow(newestDay);
        }
    }

    private static void file(ViewSpec.TreeNode n, Consumer<Path> open) {
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            open.accept(Path.of(SourceLinks.path(n.link())));
        }
    }

    /** The icon of a node: folders (work folders apart), then files by name and extension. */
    static String iconName(ViewSpec.TreeNode n) {
        String name = n.label().toLowerCase(Locale.ROOT);
        if (!n.link().startsWith(SourceLinks.SCHEME)) {
            return name.equals("work") ? "folder-work" : "folder";
        }
        String ext = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);
        if (name.contains("plan") && ext.equals("md")) {
            return "plan";
        }
        if (name.startsWith("read-")) {
            return "read";
        }
        if (name.equals("alpha.md")) {
            return "index";
        }
        return switch (ext) {
            case "md", "markdown" -> "markdown";
            case "java", "kt", "kts", "py", "js", "ts", "go", "clj", "rs", "c", "h", "cpp", "scala", "groovy" -> "code";
            case "json", "jsonl", "yaml", "yml", "toml", "xml", "properties", "conf" -> "json";
            case "sh", "zsh", "bash" -> "terminal";
            case "sql" -> "database";
            case "csv", "tsv", "parquet" -> "table";
            case "png", "jpg", "jpeg", "gif", "svg", "webp" -> "image";
            case "jfr" -> "chart";
            case "patch", "diff" -> "diff";
            case "txt", "log", "out" -> "text";
            default -> "file";
        };
    }
}
