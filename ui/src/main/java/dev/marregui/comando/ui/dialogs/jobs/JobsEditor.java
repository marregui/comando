package dev.marregui.comando.ui.dialogs.jobs;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The kit's scheduled jobs as a list with the selected one's fields beside it (record 117): add, remove, and the
 * fields of a job of {@code comando-kit.json}. Each field is checked as it is typed, with {@link KitJob#problems}; a
 * field that fails says why under the form, the list marks its job, and the list can be saved only when every job
 * passes. Under the fields, read-only, how the scheduler holds the job: installed and loaded, its last run and its
 * next. A job's row menu runs it now, opens its log or removes it. EDT only.
 */
public final class JobsEditor {

    /** What the window does for a job's row: pick its program, run it now, open its log. */
    public interface Actions {
        /** A program inside {@code bin}, chosen; empty when none was. */
        Optional<Path> program(Component owner, Path bin);

        void run(String name);

        void openLog(Path log);
    }

    /** A job of the list as edited; {@link #was} names the job it started as, empty for one added here. */
    static final class Row {
        String name;
        String description;
        final boolean[] days = new boolean[7];
        String time;
        String program;
        String arguments;
        String env;
        String log;
        boolean enabled;
        final String was;

        Row(KitJob j, String was) {
            name = j.name();
            description = j.description();
            for (int d = 0; d < 7; d++) {
                days[d] = j.days().contains(KitJob.WEEKDAYS.get(d));
            }
            time = j.time();
            program = j.program();
            arguments = KitJob.line(j.arguments());
            env = j.envLines();
            log = j.log();
            enabled = j.enabled();
            this.was = was;
        }

        KitJob job() {
            List<String> d = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                if (days[i]) {
                    d.add(KitJob.WEEKDAYS.get(i));
                }
            }
            List<String> command = new ArrayList<>();
            command.add(program.strip());
            command.addAll(KitJob.words(arguments));
            // the description as written: kit.py takes outer spaces, and stripping them would change an untouched job
            return new KitJob(name.strip(), description, d, time.strip(), command, KitJob.envOf(env), log.strip(), enabled);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<KitJob> base;
    private final Map<String, KitJobs.Entry> facts = new HashMap<>();
    private final Path root;
    private final Actions actions;
    private final DefaultListModel<Row> rows = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(rows);
    private final JTextField name = new JTextField(24);
    private final JTextField description = new JTextField(24);
    private final JToggleButton[] days = new JToggleButton[7];
    private final JTextField time = new JTextField(5);
    private final JTextField program = new JTextField(24);
    private final JButton chooseProgram = new JButton("…");
    private final JTextField arguments = new JTextField(24);
    private final JTextArea env = new JTextArea(3, 24);
    private final JTextField log = new JTextField(24);
    private final JCheckBox enabled = new JCheckBox("runs on its schedule; off pauses it");
    private final Map<String, JComponent> inputs = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private final JLabel message = new JLabel(" ");
    private final JLabel state = new JLabel(" ");
    private final JButton add;
    private final JButton remove;
    private final JPanel body = new JPanel(new BorderLayout(Theme.GAP, Theme.GAP));
    /** What {@code kit.py} said when it refused the list, until the next edit; empty when none. */
    private String refused = "";
    private Runnable changed = () -> {
    };
    private boolean loading;

    /** Edits {@code jobs}, the kit's at {@code root}, as {@code kit.py} last reported them. */
    public JobsEditor(List<KitJobs.Entry> jobs, Path root, Actions actions) {
        this.base = jobs.stream().map(KitJobs.Entry::job).toList();
        this.root = root;
        this.actions = actions;
        for (KitJobs.Entry e : jobs) {
            facts.put(e.job().name(), e);
            rows.addElement(new Row(e.job(), e.job().name()));
        }
        add = tool("add", "Add a job: name it, pick its days, time and program");
        remove = tool("close", "Take the selected job out: Save unloads it and removes it from the kit");
        build();
        if (!rows.isEmpty()) {
            list.setSelectedIndex(0);
        }
        load();
    }

    public JComponent component() {
        return body;
    }

    /** Runs whenever the list or a field changes: the window enables Save. */
    public void onChange(Runnable r) {
        changed = r;
    }

    /** Selects the job named {@code job}, when there is one. */
    public void select(String job) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).name.equals(job)) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Every job as edited; empty while any fails a check. */
    public Optional<List<KitJob>> jobs() {
        return valid() ? Optional.of(edited()) : Optional.empty();
    }

    private List<KitJob> edited() {
        List<KitJob> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(rows.get(i).job());
        }
        return out;
    }

    /** Whether the list differs from the one the window opened with, after the canonical form of each job. */
    public boolean dirty() {
        List<KitJob> now = edited();
        if (now.size() != base.size()) {
            return true;
        }
        for (int i = 0; i < now.size(); i++) {
            if (!same(now.get(i), base.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(KitJob a, KitJob b) {
        return a.name().equals(b.name()) && a.description().equals(b.description()) && a.when().equals(b.when())
                && a.command().equals(b.command()) && a.env().equals(b.env()) && a.log().equals(b.log()) && a.enabled() == b.enabled();
    }

    /** Whether every job passes, so the list can be saved. */
    public boolean valid() {
        List<KitJob> now = edited();
        for (int i = 0; i < now.size(); i++) {
            if (!KitJob.problems(now, i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Job {@code i}'s problems by field. */
    Map<String, String> problems(int i) {
        return KitJob.problems(edited(), i);
    }

    /** Says in the message line what {@code kit.py} refused, until the next edit. */
    public void refused(String why) {
        refused = why;
        refresh();
    }

    // ---- the list's actions

    /** Adds a job after the others, selected: a free name, every weekday at 09:00, no program yet. */
    void add() {
        String n = "job";
        for (int k = 2; taken(n); k++) {
            n = "job-" + k;
        }
        rows.addElement(new Row(new KitJob(n, "", KitJob.WEEKDAYS.subList(0, 5), "09:00", List.of(), Map.of(), "", true), ""));
        list.setSelectedIndex(rows.size() - 1);
        list.ensureIndexIsVisible(rows.size() - 1);
        load();
        edited(true);
    }

    private boolean taken(String n) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).name.strip().equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Takes the selected job out of the list; the next one is selected. */
    void remove() {
        int i = list.getSelectedIndex();
        if (i < 0) {
            return;
        }
        rows.remove(i);
        if (!rows.isEmpty()) {
            list.setSelectedIndex(Math.min(i, rows.size() - 1));
        }
        load();
        edited(true);
    }

    JList<Row> list() {
        return list;
    }

    /** The field for {@code key} (a {@link KitJob} field name, or {@code enabled}), for tests. */
    JComponent field(String key) {
        return switch (key) {
            case KitJob.NAME_FIELD -> name;
            case KitJob.DESCRIPTION_FIELD -> description;
            case KitJob.TIME_FIELD -> time;
            case KitJob.COMMAND_FIELD -> program;
            case "arguments" -> arguments;
            case KitJob.ENV_FIELD -> env;
            case KitJob.LOG_FIELD -> log;
            case "enabled" -> enabled;
            default -> throw new IllegalArgumentException(key);
        };
    }

    /** The toggle of weekday {@code d}, 0 for Monday. */
    JToggleButton day(int d) {
        return days[d];
    }

    String messageShown() {
        return message.getText().strip();
    }

    String stateShown() {
        return state.getText().strip();
    }

    /** The menu of the job at row {@code i}: run it now and open its log, for a job the kit already has; remove it. */
    JPopupMenu menuFor(int i) {
        JPopupMenu menu = new JPopupMenu();
        Row r = rows.get(i);
        KitJobs.Entry e = facts.get(r.was);
        JMenuItem run = new JMenuItem("Run now", Icons.get("play"));
        run.setToolTipText("Start it through the scheduler; its output goes to its log");
        run.setEnabled(e != null && e.job().enabled() && !e.running());
        run.addActionListener(a -> actions.run(r.was));
        menu.add(run);
        JMenuItem open = new JMenuItem("Open the log", Icons.get("log"));
        open.setToolTipText("Open the file the job writes its output to");
        open.setEnabled(e != null && !e.logPath().isEmpty());
        open.addActionListener(a -> actions.openLog(Path.of(e.logPath())));
        menu.add(open);
        JMenuItem drop = new JMenuItem("Remove", Icons.get("close"));
        drop.setToolTipText("Take the job out of the list; Save unloads it and removes it from the kit");
        drop.addActionListener(a -> {
            list.setSelectedIndex(i);
            remove();
        });
        menu.add(drop);
        return menu;
    }

    /** How the scheduler holds job {@code r}, in one line; a job added or renamed here is not the scheduler's yet. */
    String stateOf(Row r) {
        KitJobs.Entry e = facts.get(r.was);
        if (e == null || !r.was.equals(r.name.strip())) {
            return "new: Save installs it";
        }
        List<String> parts = new ArrayList<>();
        if (!e.job().enabled()) {
            parts.add("paused");
        } else if (e.pending()) {
            parts.add(!e.installed() ? "not installed" : Boolean.FALSE.equals(e.loaded()) ? "not loaded" : "out of date");
        } else {
            parts.add("installed");
        }
        if (e.running()) {
            parts.add("running");
        }
        parts.add(e.lastStart().isEmpty() ? "no run recorded" : "last " + when(e.lastStart()) + " exit " + e.lastExit());
        if (!e.next().isEmpty()) {
            parts.add("next " + when(e.next()));
        }
        return String.join(" · ", parts);
    }

    /** {@code 2026-10-28T19:49:59+01:00} as {@code Wed 10-28 19:49}. */
    static String when(String iso) {
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso);
            String d = t.getDayOfWeek().name();
            return d.charAt(0) + d.substring(1, 3).toLowerCase(Locale.ROOT)
                    + String.format(Locale.ROOT, " %02d-%02d %02d:%02d", t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    // ---- the form

    private void build() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Jobs");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value instanceof Row r) {
                    boolean bad = i < rows.size() && !problems(i).isEmpty();
                    KitJobs.Entry e = facts.get(r.was);
                    boolean failed = e != null && e.failed();
                    c.setText(Plain.text((bad ? "✗ " : "") + (r.name.isBlank() ? "(no name)" : r.name) + "   " + r.job().when()
                            + (e != null && e.running() ? " · running" : "")));
                    if (!sel && (bad || failed)) {
                        c.setForeground(Theme.DANGER);
                    } else if (!sel && !r.enabled) {
                        c.setForeground(Theme.MUTED_STRONG);
                    }
                    c.setIcon(Icons.folder("folder-jobs"));
                    c.setToolTipText(failed ? "Its last run ended with exit " + e.lastExit() : null);
                }
                return c;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                load();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (!e.isPopupTrigger() || i < 0) {
                    return;
                }
                list.setSelectedIndex(i);
                menuFor(i).show(list, e.getX(), e.getY());
            }
        });
        add.addActionListener(e -> add());
        remove.addActionListener(e -> remove());

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(260, 260));
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
        listButtons.add(add);
        listButtons.add(remove);
        JPanel left = new JPanel(new BorderLayout(0, Theme.GAP));
        left.add(listScroll, BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);

        JPanel week = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
        for (int d = 0; d < 7; d++) {
            String w = KitJob.WEEKDAYS.get(d);
            days[d] = new JToggleButton(w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT));
            days[d].setToolTipText("Run on " + w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT) + "days; all seven: daily");
            days[d].getAccessibleContext().setAccessibleName(w);
            days[d].addActionListener(e -> store());
            week.add(days[d]);
        }
        JPanel when = new JPanel(new BorderLayout(Theme.GAP, 0));
        when.add(week, BorderLayout.CENTER);
        when.add(time, BorderLayout.EAST);
        time.setToolTipText("The time it starts, HH:MM, 24 hours, local time");
        time.getAccessibleContext().setAccessibleName("Time");
        chooseProgram.setToolTipText("Choose a program in the kit's bin/");
        chooseProgram.addActionListener(e -> actions.program(body, root.resolve("bin")).ifPresent(f -> {
            Path abs = f.toAbsolutePath().normalize();
            Path r = root.toAbsolutePath().normalize();
            if (!abs.startsWith(r)) {
                refused("the program must be inside " + r + ": not taken");
            } else {
                program.setText(r.relativize(abs).toString().replace('\\', '/'));
            }
        }));
        env.setLineWrap(false);

        JPanel form = new JPanel(new GridBagLayout());
        int y = 0;
        y = row(form, y, "Name", name, KitJob.NAME_FIELD, "The job's name: lower case, digits and -; unique");
        y = row(form, y, "Description", description, KitJob.DESCRIPTION_FIELD, "One line on what it does, at most 80 characters");
        y = row(form, y, "Runs", when, KitJob.DAYS_FIELD, "The days it runs on and the time it starts; all seven days: daily");
        inputs.put(KitJob.TIME_FIELD, time);
        labels.put(KitJob.TIME_FIELD, "Time");
        y = row(form, y, "Program", withButton(program, chooseProgram), KitJob.COMMAND_FIELD,
                lines("The program it runs, a path inside the kit, such as bin/kb-drift", "{scheduler} anywhere becomes launchd, systemd or schtasks"));
        y = row(form, y, "Arguments", arguments, "arguments", "The program's arguments, separated by spaces; quote one that holds a space");
        y = row(form, y, "Environment", new JScrollPane(env), KitJob.ENV_FIELD, "Variables it adds, one NAME=value per line");
        y = row(form, y, "Log", log, KitJob.LOG_FIELD, "The file its output goes to, inside the kit; empty: .kit-jobs/<name>.log");
        y = row(form, y, "Enabled", enabled, "enabled", "Off pauses the job: it stays in the list, unloaded from the scheduler");
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 1;
        g.gridy = y;
        g.anchor = GridBagConstraints.LINE_START;
        g.insets = new Insets(Theme.GAP, 0, 0, 0);
        message.getAccessibleContext().setAccessibleName("Problem");
        form.add(message, g);
        g.gridy = y + 1;
        g.insets = new Insets(2, 0, 0, 0);
        state.setForeground(Theme.MUTED_STRONG);
        state.getAccessibleContext().setAccessibleName("Job state");
        form.add(state, g);
        g.gridy = y + 2;
        g.weighty = 1;
        form.add(new JPanel(), g);

        for (JTextComponent f : List.of(name, description, time, program, arguments, env, log)) {
            f.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    store();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    store();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    store();
                }
            });
        }
        enabled.addActionListener(e -> store());

        body.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        body.add(left, BorderLayout.WEST);
        body.add(form, BorderLayout.CENTER);
    }

    private int row(JPanel form, int y, String label, JComponent field, String key, String tip) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = y;
        g.anchor = GridBagConstraints.LINE_END;
        g.insets = new Insets(y == 0 ? 0 : 2, 0, 0, Theme.GAP);
        JLabel caption = new JLabel(label);
        caption.setToolTipText(tip);
        form.add(caption, g);
        g.gridx = 1;
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(y == 0 ? 0 : 2, 0, 0, 0);
        form.add(field, g);
        JComponent input = key.equals(KitJob.DAYS_FIELD) ? field : field(key);
        input.setToolTipText(tip);
        input.getAccessibleContext().setAccessibleName(label);
        caption.setLabelFor(input);
        inputs.put(key, input);
        labels.put(key, label);
        return y + 1;
    }

    private static JPanel withButton(JComponent field, JButton button) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        p.add(button, BorderLayout.EAST);
        return p;
    }

    private static String lines(String... lines) {
        return "<html>" + String.join("<br>", List.of(lines).stream().map(MarkdownHtml::escape).toList()) + "</html>";
    }

    /** Fills the fields with the selected job's; with none selected they are empty and off. */
    private void load() {
        Row r = list.getSelectedValue();
        loading = true;
        try {
            boolean on = r != null;
            for (JComponent c : List.of(name, description, time, program, chooseProgram, arguments, env, log, enabled, remove)) {
                c.setEnabled(on);
            }
            for (int d = 0; d < 7; d++) {
                days[d].setEnabled(on);
                days[d].setSelected(on && r.days[d]);
            }
            name.setText(on ? r.name : "");
            description.setText(on ? r.description : "");
            time.setText(on ? r.time : "");
            program.setText(on ? r.program : "");
            arguments.setText(on ? r.arguments : "");
            env.setText(on ? r.env : "");
            log.setText(on ? r.log : "");
            enabled.setSelected(on && r.enabled);
        } finally {
            loading = false;
        }
        refresh();
    }

    /** Takes the fields into the selected job. */
    private void store() {
        Row r = list.getSelectedValue();
        if (loading || r == null) {
            return;
        }
        r.name = name.getText();
        r.description = description.getText();
        for (int d = 0; d < 7; d++) {
            r.days[d] = days[d].isSelected();
        }
        r.time = time.getText();
        r.program = program.getText();
        r.arguments = arguments.getText();
        r.env = env.getText();
        r.log = log.getText();
        r.enabled = enabled.isSelected();
        edited(true);
    }

    private void edited(boolean byUser) {
        if (byUser) {
            refused = "";
        }
        refresh();
        changed.run();
    }

    /** Shows the selected job's problems and state, marks the list, and enables what applies. */
    private void refresh() {
        int i = list.getSelectedIndex();
        Map<String, String> p = i < 0 ? Map.of() : problems(i);
        inputs.forEach((key, field) -> field.putClientProperty("JComponent.outline", p.containsKey(key) ? "error" : null));
        Map.Entry<String, String> first = p.entrySet().stream().findFirst().orElse(null);
        String text = !refused.isEmpty() ? refused : first != null ? labels.getOrDefault(first.getKey(), first.getKey()) + ": " + first.getValue() : "";
        message.setText(text.isEmpty() ? " " : Plain.text(text));
        message.setForeground(Theme.DANGER);
        state.setText(i < 0 ? " " : Plain.text(stateOf(rows.get(i))));
        list.repaint();
        changed.run();
    }

    private static JButton tool(String icon, String tip) {
        JButton b = new JButton(Icons.get(icon));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        return b;
    }

    /** For tests: the problems of the selected job as the form shows them, in field order. */
    Map<String, String> problemsShown() {
        int i = list.getSelectedIndex();
        return i < 0 ? Map.of() : new LinkedHashMap<>(problems(i));
    }
}
