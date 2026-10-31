package dev.marregui.comando.ui.dialogs.folders;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitFolders;
import dev.marregui.comando.core.resource.FolderKind;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

/**
 * The Files tab's folders as a list with the selected one's fields beside it: add (a folder chosen first), remove,
 * move up and down, and the fields of an entry of {@code folders.json}. A folder lies inside Claude Code's folder
 * ({@code ~/.claude}) and is set only by choosing it. Each field is checked as it is typed, with {@link FolderKind}'s
 * rules, a name used once in {@code folders.json} and a folder that exists and can be read; a field that fails says
 * why beside it, the list marks its entry, and the list can be saved only when every entry passes. Whether a folder
 * exists, and the git repository it belongs to, are looked up off the EDT. EDT only.
 */
public final class FoldersEditor {

    /** Picks a folder for {@code +}, or a file for the index and the listing: the desktop's choosers, or a test's. */
    public interface Chooser {
        Optional<Path> folder(Component owner, Path start);

        /** A file, starting in {@code start}; none by default. */
        default Optional<Path> file(final Component owner, final Path start) {
            return Optional.empty();
        }
    }

    /** The index files a new folder takes, the first found in it. */
    static final List<String> INDEX_GUESSES = List.of("00_START_HERE.md", "INDEX.md", "README.md", "MEMORY.md");

    static final String NAME = "name";
    static final String PATH = "path";
    static final String INDEX = "index";
    static final String EXCLUDES = "excludes";
    static final String ICON = "icon";
    static final String DELETABLE = "deletable";
    static final String LISTED_IN = "listedIn";
    static final String GROUP = "group";

    /** What a folder's path is on disk, as looked up off the EDT. */
    record Facts(boolean exists, boolean readable, Path gitRoot) {
    }

    /** What an index file is on disk, as looked up off the EDT. */
    record IndexFacts(boolean exists, boolean folderExists) {
    }

    /** A folder of the list as edited: its fields by {@link FolderKind}'s attribute names. */
    static final class Row {
        final Map<String, String> fields;

        Row(final Map<String, String> fields) {
            this.fields = new LinkedHashMap<>(fields);
        }

        String get(final String key) {
            return fields.getOrDefault(key, "");
        }

        @Override
        public String toString() {
            return get(NAME);
        }
    }

    private final List<FolderRoot> base;
    private final Path claudeDir;
    private final RepoChanges repos;
    private final Chooser chooser;
    private final DefaultListModel<Row> rows = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(rows);
    private final JTextField name = new JTextField(24);
    private final JTextField path = new JTextField(24);
    private final JButton choosePath = new JButton("…");
    private final JTextField index = new JTextField(24);
    private final JButton chooseIndex = new JButton("…");
    private final JTextField excludes = new JTextField(24);
    private final JComboBox<String> icon = new JComboBox<>(FolderRoot.ICONS.toArray(String[]::new));
    private final JCheckBox deletable = new JCheckBox("its files and subfolders can go to the Trash");
    private final JTextField listedIn = new JTextField(24);
    private final JTextField group = new JTextField(24);
    private final JButton chooseListing = new JButton("…");
    /** The fields by key, outlined in red while they fail. */
    private final Map<String, JComponent> inputs = new HashMap<>();
    /** The labels by key, naming each field in the message line. */
    private final Map<String, String> labels = new HashMap<>();
    /** One line under the form: the selected entry's first problem in red, else a note in grey. */
    private final JLabel message = new JLabel(" ");
    private final JLabel facts = new JLabel(" ");
    private final JButton add;
    private final JButton remove;
    private final JButton up;
    private final JButton down;
    private final JPanel body = new JPanel(new BorderLayout(Theme.GAP, Theme.GAP));
    /** Each path's facts as last looked up; a path not yet looked up is being checked. EDT only. */
    private final Map<String, Facts> looked = new HashMap<>();
    /** Each index file's facts as last looked up, by its full path. EDT only. */
    private final Map<String, IndexFacts> indexes = new HashMap<>();
    /** A pick refused, said in the message line until the next edit; empty when none. */
    private String refused = "";
    private Runnable changed = () -> {
    };
    /** True while code fills the fields, so filling them is not taken for an edit. */
    private boolean loading;

    /** Edits {@code folders}, which lie inside {@code claudeDir}. */
    public FoldersEditor(final List<FolderRoot> folders, final Path claudeDir, final RepoChanges repos, final Chooser chooser) {
        this.base = List.copyOf(folders);
        this.claudeDir = claudeDir.toAbsolutePath().normalize();
        this.repos = repos;
        this.chooser = chooser;
        add = tool("add", "Add a folder: choose it inside " + configShown());
        remove = tool("close", "Take the selected folder out of the Files tab; nothing on disk changes");
        up = tool("↑", KeyText.of("Move the selected folder up (⌥↑)"));
        down = tool("↓", KeyText.of("Move the selected folder down (⌥↓)"));
        for (final FolderRoot f : folders) {
            rows.addElement(new Row(FolderKind.INSTANCE.toAttributes(f)));
        }
        build();
        rows.elements().asIterator().forEachRemaining(r -> {
            lookUp(r.get(PATH));
            lookUpIndex(r);
        });
        if (!rows.isEmpty()) {
            list.setSelectedIndex(0);
        }
        load();
    }

    public JComponent component() {
        return body;
    }

    /** Runs whenever the list or a field changes: the window enables Save. */
    public void onChange(final Runnable r) {
        changed = r;
    }

    /** Selects the folder named {@code folder}, when there is one. */
    public void select(final String folder) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get(NAME).equals(folder)) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Every entry of {@code folders.json} as edited; empty while any fails a check. */
    public Optional<List<FolderRoot>> folders() {
        if (!problemsOfAll().isEmpty()) {
            return Optional.empty();
        }
        final List<FolderRoot> edited = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            try {
                edited.add(FolderKind.INSTANCE.fromAttributes(rows.get(i).fields));
            } catch (final IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.of(List.copyOf(edited));
    }

    /** The list the editor started from. */
    public List<FolderRoot> base() {
        return base;
    }

    /** Whether the list differs from the one it started from. */
    public boolean dirty() {
        if (rows.size() != base.size()) {
            return true;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (!normalized(rows.get(i).fields).equals(normalized(FolderKind.INSTANCE.toAttributes(base.get(i))))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> normalized(final Map<String, String> fields) {
        final Map<String, String> out = new LinkedHashMap<>();
        fields.forEach((k, v) -> out.put(k, v.strip()));
        return out;
    }

    /** Whether every folder passes, so the list can be saved. */
    public boolean valid() {
        return problemsOfAll().isEmpty();
    }

    /** The problems of every folder, by its place in the list. */
    Map<Integer, Map<String, String>> problemsOfAll() {
        final Map<Integer, Map<String, String>> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            final Map<String, String> p = problems(i);
            if (!p.isEmpty()) {
                out.put(i, p);
            }
        }
        return out;
    }

    /**
     * Entry {@code i}'s problems by field: a name missing or used twice in {@code folders.json}, a folder not chosen,
     * not a full path, not there, not readable, still being looked up, or outside {@code ~/.claude}; an index outside
     * the folder or in a folder that does not exist; a listing file that is no file name.
     */
    Map<String, String> problems(final int i) {
        final Row r = rows.get(i);
        final Map<String, String> out = new LinkedHashMap<>();
        final String n = r.get(NAME).strip();
        if (n.isEmpty()) {
            out.put(NAME, "a name is needed");
        } else {
            for (int k = 0; k < rows.size(); k++) {
                if (k != i && rows.get(k).get(NAME).strip().equals(n)) {
                    out.put(NAME, "another folder has this name");
                    break;
                }
            }
        }
        final String p = r.get(PATH).strip();
        if (p.isEmpty()) {
            out.put(PATH, "choose the folder");
        } else if (!Path.of(HomeDir.expand(p)).isAbsolute()) {
            out.put(PATH, "a full path, such as ~/notes");
        } else {
            final Facts f = looked.get(HomeDir.expand(p));
            if (f == null) {
                out.put(PATH, "checking…");
            } else if (!f.exists()) {
                out.put(PATH, "no such folder");
            } else if (!f.readable()) {
                out.put(PATH, "cannot be read");
            } else if (!Path.of(HomeDir.expand(p)).toAbsolutePath().normalize().startsWith(claudeDir)) {
                out.put(PATH, "must be inside " + HomeDir.shorten(claudeDir.toString()));
            }
        }
        final String ix = r.get(INDEX).strip();
        if (!ix.isEmpty() && (Path.of(ix).isAbsolute() || Path.of(ix).normalize().startsWith(".."))) {
            out.put(INDEX, "must be inside the folder");
        } else if (!ix.isEmpty() && !out.containsKey(PATH)) {
            final IndexFacts f = indexes.get(indexPath(r).toString());
            if (f != null && !f.exists() && !f.folderExists()) {
                out.put(INDEX, "its folder does not exist");
            }
        }
        final String listing = r.get(LISTED_IN).strip();
        if (Boolean.parseBoolean(r.get(DELETABLE)) && !listing.isEmpty()
                && (listing.contains("/") || listing.contains("\\") || listing.equals(".") || listing.equals(".."))) {
            out.put(LISTED_IN, "a file name, such as INDEX.md");
        }
        if (out.isEmpty()) {
            try {
                FolderKind.INSTANCE.fromAttributes(r.fields);
            } catch (final IllegalArgumentException e) {
                out.put(NAME, e.getMessage());
            }
        }
        return out;
    }

    // ---- the list's actions

    /** Adds a folder chosen first: named after it (made unique), its index the first guess found in it, selected. */
    void add() {
        chooser.folder(body, claudeDir).ifPresent(this::add);
    }

    /** Adds {@code folder} after the others, selected; a folder outside {@code ~/.claude} is refused. */
    void add(final Path folder) {
        final Path dir = folder.toAbsolutePath().normalize();
        if (!dir.startsWith(claudeDir)) {
            refuse(HomeDir.shorten(dir.toString()) + " is outside " + configShown() + ": not added");
            return;
        }
        final String base = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        String n = base;
        for (int k = 2; nameTaken(n); k++) {
            n = base + " " + k;
        }
        String guess = "";
        for (final String g : INDEX_GUESSES) {
            if (Files.isRegularFile(dir.resolve(g))) {
                guess = g;
                break;
            }
        }
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(NAME, n);
        fields.put(PATH, HomeDir.shorten(dir.toString()));
        fields.put(INDEX, guess);
        fields.put(EXCLUDES, "");
        fields.put(ICON, FolderRoot.FOLDER);
        fields.put(DELETABLE, "false");
        fields.put(LISTED_IN, "");
        fields.put(GROUP, "");
        rows.addElement(new Row(fields));
        lookUp(fields.get(PATH));
        lookUpIndex(rows.lastElement());
        list.setSelectedIndex(rows.size() - 1);
        list.ensureIndexIsVisible(rows.size() - 1);
        edited();
    }

    /**
     * "Use the kit's folders": the folders of {@code kit} the list lacks (none of its rows shows that folder or has
     * that name) go first, in the kit's order, the first selected; answers how many were added.
     */
    int addFirst(final List<FolderRoot> kit) {
        final List<FolderRoot> have = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            have.add(new FolderRoot(rows.get(i).get(NAME).strip(), HomeDir.expand(rows.get(i).get(PATH).strip()), "", List.of()));
        }
        final List<FolderRoot> missing = KitFolders.missing(have, kit);
        for (int k = 0; k < missing.size(); k++) {
            final Row r = new Row(FolderKind.INSTANCE.toAttributes(missing.get(k)));
            rows.add(k, r);
            lookUp(r.get(PATH));
            lookUpIndex(r);
        }
        if (!missing.isEmpty()) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
            load();
            edited();
        }
        return missing.size();
    }

    private boolean nameTaken(final String n) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get(NAME).strip().equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Takes the selected folder out of the list; the next one is selected. */
    void remove() {
        final int i = list.getSelectedIndex();
        if (i < 0) {
            return;
        }
        rows.remove(i);
        if (!rows.isEmpty()) {
            list.setSelectedIndex(Math.min(i, rows.size() - 1));
        }
        load();
        edited();
    }

    /** Moves the selected folder {@code by} places, within the list. */
    void move(final int by) {
        final int i = list.getSelectedIndex();
        final int j = i + by;
        if (i < 0 || j < 0 || j >= rows.size()) {
            return;
        }
        final Row r = rows.remove(i);
        rows.add(j, r);
        list.setSelectedIndex(j);
        edited();
    }

    JList<Row> list() {
        return list;
    }

    /** The field holding attribute {@code key}, for tests. */
    JComponent field(final String key) {
        return switch (key) {
            case NAME -> name;
            case PATH -> path;
            case INDEX -> index;
            case EXCLUDES -> excludes;
            case ICON -> icon;
            case DELETABLE -> deletable;
            case LISTED_IN -> listedIn;
            case GROUP -> group;
            default -> throw new IllegalArgumentException(key);
        };
    }

    /** The selected entry's problem with field {@code key}, as its outline and the message line show it; or empty. */
    String problemShown(final String key) {
        final int i = list.getSelectedIndex();
        return i < 0 ? "" : problems(i).getOrDefault(key, "");
    }

    /** The message line under the form. */
    String messageShown() {
        return message.getText().strip();
    }

    String factsShown() {
        return facts.getText();
    }

    // ---- facts looked up off the EDT

    /** Looks up whether {@code shown} (as typed) exists and the repository it belongs to, then checks again. */
    private void lookUp(final String shown) {
        final String p = HomeDir.expand(shown.strip());
        if (p.isEmpty() || !Path.of(p).isAbsolute() || looked.containsKey(p)) {
            return;
        }
        UiErrands.start("folder-facts", () -> {
            final Facts f = facts(Path.of(p));
            Edt.later(() -> {
                looked.put(p, f);
                refresh();
            });
        });
    }

    /** Looks up whether {@code r}'s index file and its folder exist, then checks again. */
    private void lookUpIndex(final Row r) {
        final String ix = r.get(INDEX).strip();
        final String p = HomeDir.expand(r.get(PATH).strip());
        if (ix.isEmpty() || p.isEmpty() || !Path.of(p).isAbsolute() || Path.of(ix).isAbsolute()) {
            return;
        }
        final Path file = indexPath(r);
        if (indexes.containsKey(file.toString())) {
            return;
        }
        UiErrands.start("folder-facts", () -> {
            final IndexFacts f = new IndexFacts(Files.isRegularFile(file), file.getParent() != null && Files.isDirectory(file.getParent()));
            Edt.later(() -> {
                indexes.put(file.toString(), f);
                refresh();
            });
        });
    }

    private static Path indexPath(final Row r) {
        return Path.of(HomeDir.expand(r.get(PATH).strip())).resolve(r.get(INDEX).strip()).toAbsolutePath().normalize();
    }

    /** Whether {@code p} is a readable folder, and the git repository holding it; reads the disk. */
    static Facts facts(final Path p) {
        final boolean exists = Files.isDirectory(p);
        Path git = null;
        for (Path q = p.toAbsolutePath().normalize(); q != null; q = q.getParent()) {
            if (Files.exists(q.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                git = q;
                break;
            }
        }
        return new Facts(exists, exists && Files.isReadable(p), git);
    }

    /**
     * The git repository holding {@code r}'s folder: "repo: ~/.claude · main · 1 uncommitted", as read; "repo: none".
     */
    String factsOf(final Row r) {
        final String p = HomeDir.expand(r.get(PATH).strip());
        final Facts f = looked.get(p);
        if (f == null || !f.exists()) {
            return " ";
        }
        if (f.gitRoot() == null) {
            return "repo: none";
        }
        final String state = repos.repoAt(f.gitRoot()).map(s -> s.error().isEmpty()
                ? " · " + (s.branch().isEmpty() ? "detached HEAD" : s.branch()) + " · " + s.changes().size() + (s.truncated() ? "+" : "") + " uncommitted"
                : " · cannot be read").orElse("");
        return "repo: " + HomeDir.shorten(f.gitRoot().toString()) + state;
    }

    // ---- the form

    private void build() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Folders");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int i, final boolean sel, final boolean focus) {
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value instanceof Row r) {
                    final boolean bad = i < rows.size() && !problems(i).isEmpty() && !"checking…".equals(problems(i).get(PATH));
                    c.setText(Plain.text((bad ? "✗ " : "") + (r.get(NAME).isBlank() ? "(no name)" : r.get(NAME))));
                    if (bad && !sel) {
                        c.setForeground(Theme.DANGER);
                    }
                    c.setIcon(Icons.folder(rowIcon(r)));
                }
                return c;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                load();
            }
        });
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "up", () -> move(-1));
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "down", () -> move(1));
        add.addActionListener(e -> add());
        remove.addActionListener(e -> remove());
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));

        final JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(220, 220));
        final JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
        listButtons.add(add);
        listButtons.add(remove);
        listButtons.add(up);
        listButtons.add(down);
        final JPanel left = new JPanel(new BorderLayout(0, Theme.GAP));
        left.add(listScroll, BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);

        icon.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int i, final boolean sel, final boolean focus) {
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value instanceof String s) {
                    c.setIcon(Icons.folder(FolderRoot.FOLDER.equals(s) ? "folder" : "folder-" + s));
                }
                return c;
            }
        });
        choosePath.setToolTipText("Choose a folder inside " + configShown());
        path.setEditable(false); // chosen, never typed: it exists when chosen
        choosePath.addActionListener(e -> {
            final String p = HomeDir.expand(path.getText().strip());
            final Path start = p.isEmpty() || !Path.of(p).isAbsolute() ? claudeDir : Path.of(p);
            chooser.folder(body, start).map(dir -> dir.toAbsolutePath().normalize()).ifPresent(dir -> {
                if (!dir.startsWith(claudeDir)) {
                    refuse(HomeDir.shorten(dir.toString()) + " is outside " + configShown() + ": not taken");
                } else {
                    path.setText(HomeDir.shorten(dir.toString()));
                }
            });
        });
        chooseIndex.setToolTipText("Choose a file in the folder");
        chooseIndex.addActionListener(e -> chooseInFolder("index").ifPresent(f -> index.setText(f.toString().replace('\\', '/'))));
        chooseListing.setToolTipText("Choose a file in the folder; its name is taken");
        chooseListing.addActionListener(e -> chooseInFolder("listing").ifPresent(f -> listedIn.setText(f.getFileName().toString())));

        final String home = configShown();
        final JPanel form = new JPanel(new GridBagLayout());
        int y = 0;
        y = row(form, y, "Name", name, NAME, "The row's name in the Files tab; unique");
        y = row(form, y, "Folder", withButton(path, choosePath), PATH, "The folder the row shows; chosen with …, only inside " + home);
        y = row(form, y, "Index file", withButton(index, chooseIndex), INDEX,
                lines("The folder's catalogue, one line per entry: listed first, opened by Open in the row's menu",
                        "Named but missing: Save creates it, and /reflect keeps a line in it for each entry",
                        "Empty: the folder has no index"));
        y = row(form, y, "Leave out", excludes, EXCLUDES, lines("Names hidden from the tree wherever they appear, comma-separated, any case",
                "Always hidden: names starting with a dot, " + String.join(", ", FolderRoot.ALWAYS_EXCLUDED.stream().sorted().toList())));
        y = row(form, y, "Icon", icon, ICON, "The icon of the folder's row");
        y = row(form, y, "Trash", deletable, DELETABLE, "Whether the Files tab moves the folder's files and subfolders to the Trash; never the folder");
        y = row(form, y, "Listed in", withButton(listedIn, chooseListing), LISTED_IN,
                lines("The name of the file that, in each subfolder, lists the entries beside it, such as INDEX.md",
                        "An entry moved to the Trash takes its lines out of the nearest such file above it",
                        "Missing in a subfolder: nothing to take out there; /reflect creates it with its first line",
                        "Empty, or Trash off: no file is changed"));
        y = row(form, y, "Group", group, GROUP,
                lines("A header the Files tab shows above this folder and the folders after it in the same group, such as Brain",
                        "The folders stay where they are: nothing nests, nothing opens or closes",
                        "Empty: the folder is in no group"));
        final GridBagConstraints g = new GridBagConstraints();
        g.gridx = 1;
        g.gridy = y;
        g.anchor = GridBagConstraints.LINE_START;
        g.insets = new Insets(Theme.GAP, 0, 0, 0);
        message.getAccessibleContext().setAccessibleName("Problem");
        form.add(message, g);
        g.gridy = y + 1;
        g.insets = new Insets(2, 0, 0, 0);
        facts.setForeground(Theme.MUTED_STRONG);
        facts.getAccessibleContext().setAccessibleName("Folder facts");
        form.add(facts, g);
        g.gridy = y + 2;
        g.weighty = 1;
        form.add(new JPanel(), g);

        for (final JTextField f : List.of(name, path, index, excludes, listedIn, group)) {
            f.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(final DocumentEvent e) {
                    store();
                }

                @Override
                public void removeUpdate(final DocumentEvent e) {
                    store();
                }

                @Override
                public void changedUpdate(final DocumentEvent e) {
                    store();
                }
            });
        }
        icon.addActionListener(e -> store());
        deletable.addActionListener(e -> store());

        body.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        body.add(left, BorderLayout.WEST);
        body.add(form, BorderLayout.CENTER);
    }

    /** A labelled field, its label and the field explaining it under the mouse; answers the next free grid row. */
    private int row(final JPanel form, final int y, final String label, final JComponent field, final String key, final String tip) {
        final GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = y;
        g.anchor = GridBagConstraints.LINE_END;
        g.insets = new Insets(y == 0 ? 0 : 2, 0, 0, Theme.GAP);
        final JLabel caption = new JLabel(label);
        caption.setToolTipText(tip);
        form.add(caption, g);
        g.gridx = 1;
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(y == 0 ? 0 : 2, 0, 0, 0);
        form.add(field, g);
        final JComponent input = field(key);
        input.setToolTipText(tip);
        input.getAccessibleContext().setAccessibleName(label);
        caption.setLabelFor(input);
        inputs.put(key, input);
        labels.put(key, label);
        return y + 1;
    }

    private static JPanel withButton(final JComponent field, final JButton button) {
        final JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        p.add(button, BorderLayout.EAST);
        return p;
    }

    /** A tooltip of a few lines. */
    private static String lines(final String... lines) {
        return "<html>" + String.join("<br>", List.of(lines).stream().map(MarkdownHtml::escape).toList()) + "</html>";
    }

    /** {@code ~/.claude}, or Claude Code's folder wherever it is, as shown. */
    private String configShown() {
        return HomeDir.shorten(claudeDir.toString());
    }

    /** A file chosen in the selected entry's folder, relative to it; one outside is refused, saying so. */
    private Optional<Path> chooseInFolder(final String what) {
        final Row r = list.getSelectedValue();
        if (r == null || r.get(PATH).isBlank()) {
            return Optional.empty();
        }
        final Path dir = Path.of(HomeDir.expand(r.get(PATH).strip())).toAbsolutePath().normalize();
        final Optional<Path> chosen = chooser.file(body, dir).map(f -> f.toAbsolutePath().normalize());
        if (chosen.isPresent() && !chosen.get().startsWith(dir)) {
            refuse(HomeDir.shorten(chosen.get().toString()) + " is outside the folder: not taken as its " + what);
            return Optional.empty();
        }
        return chosen.map(dir::relativize);
    }

    /** Says in the message line why a pick was not taken, until the next edit. */
    private void refuse(final String why) {
        refused = why;
        refresh();
    }

    /** Fills the fields with the selected folder's; with none selected they are empty and off. */
    private void load() {
        final Row r = list.getSelectedValue();
        loading = true;
        try {
            final boolean on = r != null;
            for (final JComponent c : List.of(name, path, choosePath, index, chooseIndex, excludes, icon, deletable, listedIn, chooseListing, group, remove)) {
                c.setEnabled(on);
            }
            name.setText(on ? r.get(NAME) : "");
            path.setText(on ? r.get(PATH) : "");
            index.setText(on ? r.get(INDEX) : "");
            excludes.setText(on ? r.get(EXCLUDES) : "");
            icon.setSelectedItem(on && FolderRoot.ICONS.contains(r.get(ICON)) ? r.get(ICON) : FolderRoot.FOLDER);
            deletable.setSelected(on && Boolean.parseBoolean(r.get(DELETABLE)));
            listedIn.setText(on ? r.get(LISTED_IN) : "");
            group.setText(on ? r.get(GROUP) : "");
        } finally {
            loading = false;
        }
        refresh();
    }

    /** Takes the fields into the selected folder. */
    private void store() {
        final Row r = list.getSelectedValue();
        if (loading || r == null) {
            return;
        }
        r.fields.put(NAME, name.getText());
        r.fields.put(PATH, path.getText());
        r.fields.put(INDEX, index.getText());
        r.fields.put(EXCLUDES, excludes.getText());
        r.fields.put(ICON, String.valueOf(icon.getSelectedItem()));
        r.fields.put(DELETABLE, Boolean.toString(deletable.isSelected()));
        r.fields.put(LISTED_IN, listedIn.getText());
        r.fields.put(GROUP, group.getText());
        refused = "";
        lookUp(path.getText());
        lookUpIndex(r);
        edited();
    }

    private void edited() {
        refresh();
        changed.run();
    }

    /** Shows the selected folder's problems and facts, marks the list, and enables what applies. */
    private void refresh() {
        final int i = list.getSelectedIndex();
        final Map<String, String> p = i < 0 ? Map.of() : problems(i);
        inputs.forEach((key, field) -> field.putClientProperty("JComponent.outline",
                p.containsKey(key) && !"checking…".equals(p.get(key)) ? "error" : null));
        final Map.Entry<String, String> first = p.entrySet().stream().findFirst().orElse(null);
        final String note = i < 0 ? "" : note(rows.get(i));
        final String text = !refused.isEmpty() ? refused : first != null ? labels.get(first.getKey()) + ": " + first.getValue() : note;
        message.setText(text.isEmpty() ? " " : Plain.text(text));
        message.setForeground(refused.isEmpty() && (first == null || "checking…".equals(first.getValue())) ? Theme.MUTED_STRONG : Theme.DANGER);
        listedIn.setEnabled(i >= 0 && deletable.isSelected());
        chooseListing.setEnabled(listedIn.isEnabled());
        up.setEnabled(i > 0);
        down.setEnabled(i >= 0 && i < rows.size() - 1);
        facts.setText(i < 0 ? " " : Plain.text(factsOf(rows.get(i))));
        list.repaint();
        changed.run();
    }

    /** What Save does with {@code r} beyond {@code folders.json}: an index file named but missing is created. */
    private String note(final Row r) {
        final String ix = r.get(INDEX).strip();
        final IndexFacts f = ix.isEmpty() ? null : indexes.get(indexPath(r).toString());
        if (f == null || f.exists()) {
            return "";
        }
        return ix + " does not exist yet: Save creates it";
    }

    private String rowIcon(final Row r) {
        final Facts f = looked.get(HomeDir.expand(r.get(PATH).strip()));
        if (f != null && f.gitRoot() != null && f.gitRoot().equals(Path.of(HomeDir.expand(r.get(PATH).strip())).toAbsolutePath().normalize())) {
            return "folder-git";
        }
        final String i = r.get(ICON);
        return FolderRoot.ICONS.contains(i) && !FolderRoot.FOLDER.equals(i) ? "folder-" + i : "folder";
    }

    private static JButton tool(final String iconOrText, final String tip) {
        final JButton b = Set.of("↑", "↓").contains(iconOrText) ? new JButton(iconOrText) : new JButton(Icons.get(iconOrText));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        return b;
    }

    private static void bind(final JComponent c, final KeyStroke key, final String name, final Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                r.run();
            }
        });
    }
}
