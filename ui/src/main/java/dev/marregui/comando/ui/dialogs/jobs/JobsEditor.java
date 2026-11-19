package dev.marregui.comando.ui.dialogs.jobs;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.InvalidPathException;
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
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.dialogs.FormRows;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The kit's scheduled jobs as a list with the selected one's fields beside it (record 117): add, remove, and the
 * fields of a job of {@code comando-kit.json}. Each field is checked as it is typed, with {@link KitJob#problems}; a
 * field that fails says why under the form, the list marks its job, and the list can be saved only when every job
 * passes. Above the fields, read-only, how the scheduler holds the job: its state, its last run and its next. Each
 * field has a hint under it and an ⓘ with the longer explanation, as the session form; the program and the log open in
 * an editor panel from their rows. A job's row menu runs it now, opens its log or removes it. EDT only.
 */
public final class JobsEditor {

    /** What the window does for a job's row: pick its program, run it now, open its program or its log. */
    public interface Actions {
        /** A program inside {@code tools}, chosen; empty when none was. */
        Optional<Path> program(Component owner, Path tools);

        void run(String name);

        /** Opens {@code file}, the job's program or its log, in an editor panel. */
        void open(Path file);
    }

    /** What the window says above the form: how a job runs and where its actions are. */
    static final String CONTEXT = "Each job runs at its times as kit.py run <name>; Save writes comando-kit.json "
            + "and updates the scheduler";
    static final String CONTEXT_ACTIONS = "Right-click a job to run it now, open its log or remove it";
    /** What the Runs row says for a service, which has no days or time. */
    static final String ALWAYS = "Always running; restarted when it exits";

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
        final boolean service;
        final boolean awake;
        final String was;

        Row(final KitJob j, final String was) {
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
            service = j.service();
            awake = j.awake();
            this.was = was;
        }

        KitJob job() {
            final List<String> d = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                if (days[i]) {
                    d.add(KitJob.WEEKDAYS.get(i));
                }
            }
            final List<String> command = new ArrayList<>();
            command.add(program.strip());
            command.addAll(KitJob.words(arguments));
            // the description as written: kit.py takes outer spaces, and stripping them would change an untouched job
            return new KitJob(name.strip(), description, d, time.strip(), command, KitJob.envOf(env), log.strip(),
                    enabled, service, awake);
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
    private final JButton openProgram = FormRows.tool("open-file", "Open the program in an editor panel");
    private final JTextField arguments = new JTextField(24);
    private final JTextArea env = new JTextArea(3, 24);
    private final JTextField log = new JTextField(24);
    private final JButton openLog = FormRows.tool("open-file", "Open the log in an editor panel");
    private final JCheckBox enabled = new JCheckBox("On: the scheduler runs it; off pauses it");
    /** The days and the time, or for a service {@link #ALWAYS}. */
    private final JPanel runs = new JPanel(new CardLayout());
    /** The fields as rows, outlined in red while they fail. */
    private final FormRows form = new FormRows();
    /** How the scheduler holds the selected job, its last run and its next, above the fields. */
    private final JLabel state = new JLabel(" ");
    private final JLabel last = new JLabel(" ");
    private final JLabel next = new JLabel(" ");
    private final JButton add;
    private final JButton remove;
    private final JPanel body = new JPanel(new BorderLayout(Theme.GAP, Theme.GAP));
    /** What {@code kit.py} said when it refused the list, until the next edit; empty when none. */
    private String refused = "";
    private Runnable changed = () -> {
    };
    private boolean loading;

    /** Edits {@code jobs}, the kit's at {@code root}, as {@code kit.py} last reported them. */
    public JobsEditor(final List<KitJobs.Entry> jobs, final Path root, final Actions actions) {
        this.base = jobs.stream().map(KitJobs.Entry::job).toList();
        this.root = root;
        this.actions = actions;
        for (final KitJobs.Entry e : jobs) {
            facts.put(e.job().name(), e);
            rows.addElement(new Row(e.job(), e.job().name()));
        }
        add = FormRows.tool("add", "Add a job: name it, pick its days, time and program");
        remove = FormRows.tool("close", "Remove the selected job; Save unloads it from the scheduler");
        build();
        FormRows.selectFirst(list);
        load();
    }

    public JComponent component() {
        return body;
    }

    /** Runs whenever the list or a field changes: the window enables Save. */
    public void onChange(final Runnable r) {
        changed = r;
    }

    /** Selects the job named {@code job}, when there is one. */
    public void select(final String job) {
        FormRows.select(list, rows, r -> r.name.equals(job));
    }

    /** Every job as edited; empty while any fails a check. */
    public Optional<List<KitJob>> jobs() {
        return valid() ? Optional.of(edited()) : Optional.empty();
    }

    private List<KitJob> edited() {
        final List<KitJob> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(rows.get(i).job());
        }
        return out;
    }

    /** Whether the list differs from the one the window opened with, after the canonical form of each job. */
    public boolean dirty() {
        final List<KitJob> now = edited();
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

    private static boolean same(final KitJob a, final KitJob b) {
        return a.name().equals(b.name()) && a.description().equals(b.description()) && a.when().equals(b.when())
                && a.command().equals(b.command()) && a.env().equals(b.env()) && a.log().equals(b.log()) && a.enabled() == b.enabled();
    }

    /** Whether every job passes, so the list can be saved. */
    public boolean valid() {
        final List<KitJob> now = edited();
        for (int i = 0; i < now.size(); i++) {
            if (!KitJob.problems(now, i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Job {@code i}'s problems by field. */
    Map<String, String> problems(final int i) {
        return KitJob.problems(edited(), i);
    }

    /** Says in the message line what {@code kit.py} refused, until the next edit. */
    public void refused(final String why) {
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

    private boolean taken(final String n) {
        return FormRows.any(rows, r -> r.name.strip().equals(n));
    }

    /** Takes the selected job out of the list; the next one is selected. */
    void remove() {
        if (FormRows.removeSelected(list, rows)) {
            load();
            edited(true);
        }
    }

    JList<Row> list() {
        return list;
    }

    /** The field for {@code key} (a {@link KitJob} field name, or {@code enabled}), for tests. */
    JComponent field(final String key) {
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
    JToggleButton day(final int d) {
        return days[d];
    }

    String messageShown() {
        return form.messageShown();
    }

    /** The status above the fields as one line: state, last run, next run. */
    String stateShown() {
        return String.join(" · ", state.getText().strip(), last.getText().strip(), next.getText().strip());
    }

    JButton openProgramButton() {
        return openProgram;
    }

    JButton openLogButton() {
        return openLog;
    }

    /** The card of the Runs row shown now: {@code days} or {@code always}. */
    String runsShown() {
        for (final Component c : runs.getComponents()) {
            if (c.isVisible()) {
                return c.getName();
            }
        }
        return "";
    }

    /** The menu of the job at row {@code i}: run it now and open its log, for a job the kit already has; remove it. */
    JPopupMenu menuFor(final int i) {
        final JPopupMenu menu = new JPopupMenu();
        final Row r = rows.get(i);
        final KitJobs.Entry e = facts.get(r.was);
        final JMenuItem run = new JMenuItem("Run now", Icons.get("play"));
        run.setToolTipText("Start it through the scheduler; its output goes to its log");
        run.setEnabled(e != null && e.job().enabled() && !e.running());
        run.addActionListener(a -> actions.run(r.was));
        menu.add(run);
        final JMenuItem open = new JMenuItem("Open the log", Icons.get("log"));
        open.setToolTipText("Open the file the job writes its output to");
        open.setEnabled(e != null && !e.logPath().isEmpty());
        open.addActionListener(a -> actions.open(Path.of(e.logPath())));
        menu.add(open);
        final JMenuItem drop = new JMenuItem("Remove", Icons.get("close"));
        drop.setToolTipText("Take the job out of the list; Save unloads it and removes it from the kit");
        drop.addActionListener(a -> {
            list.setSelectedIndex(i);
            remove();
        });
        menu.add(drop);
        return menu;
    }

    /**
     * How the scheduler holds job {@code r}: its state, its last run and its next, each a few words; a job added or
     * renamed here is not the scheduler's yet.
     */
    List<String> stateOf(final Row r) {
        final KitJobs.Entry e = facts.get(r.was);
        if (e == null || !r.was.equals(r.name.strip())) {
            return List.of("New: Save installs it", "no run yet", r.service ? "always on" : "after Save");
        }
        final String held;
        if (!e.job().enabled()) {
            held = "Paused";
        } else if (e.pending()) {
            held = !e.installed() ? "Not installed: Save installs it"
                    : Boolean.FALSE.equals(e.loaded()) ? "Not loaded: Save loads it" : "Out of date: Save updates it";
        } else {
            held = "Installed";
        }
        final String lastRun = e.lastStart().isEmpty() ? "no run recorded"
                : e.lastExit() == null ? when(e.lastStart())
                : when(e.lastStart()) + ", exit " + e.lastExit() + (e.lastExit() == 0 ? " ✓" : " ✗");
        final String nextRun = r.service ? "always on" : e.next().isEmpty() ? "none while paused" : when(e.next());
        return List.of(e.running() ? held + ", running" : held, lastRun, nextRun);
    }

    /** {@code 2026-10-28T19:49:59+01:00} as {@code Wed 10-28 19:49}. */
    static String when(final String iso) {
        try {
            final OffsetDateTime t = OffsetDateTime.parse(iso);
            final String d = t.getDayOfWeek().name();
            return d.charAt(0) + d.substring(1, 3).toLowerCase(Locale.ROOT)
                    + String.format(Locale.ROOT, " %02d-%02d %02d:%02d", t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
        } catch (final DateTimeParseException e) {
            return iso;
        }
    }

    // ---- the form

    private void build() {
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                popup(e);
            }

            private void popup(final MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                if (!e.isPopupTrigger() || i < 0) {
                    return;
                }
                list.setSelectedIndex(i);
                menuFor(i).show(list, e.getX(), e.getY());
            }
        });
        add.addActionListener(e -> add());
        remove.addActionListener(e -> remove());
        // the rows are drawn by Cell, set below: two labels where the shared look has one
        final JPanel left = FormRows.listSide(list, Row.class, (c, r, i, sel) -> { }, "Jobs", 260, this::load, add,
                remove);
        list.setCellRenderer(new Cell());

        final JPanel week = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
        for (int d = 0; d < 7; d++) {
            final String w = KitJob.WEEKDAYS.get(d);
            final String day = w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT);
            final JToggleButton t = new JToggleButton(day);
            t.setToolTipText("Run on " + day + "days; all seven days make it daily");
            t.getAccessibleContext().setAccessibleName(w);
            t.addActionListener(e -> store());
            // a day it runs on stands out: the selection's colour and bold, not only the toolkit's grey
            t.addItemListener(e -> marked(t));
            days[d] = t;
            week.add(t);
        }
        final JPanel when = new JPanel(new BorderLayout(Theme.GAP, 0));
        when.add(week, BorderLayout.CENTER);
        when.add(time, BorderLayout.EAST);
        when.setName("days");
        final JLabel always = new JLabel(ALWAYS);
        always.setName("always");
        always.setForeground(Theme.MUTED_STRONG);
        runs.add(when, "days");
        runs.add(always, "always");
        time.setToolTipText("Start time, HH:MM, 24 hours, local time");
        time.getAccessibleContext().setAccessibleName("Time");
        chooseProgram.setToolTipText("Choose another program in the kit's tools/");
        chooseProgram.getAccessibleContext().setAccessibleName("Choose the program");
        chooseProgram.addActionListener(e -> actions.program(body, root.resolve("tools")).ifPresent(f -> {
            final Path abs = f.toAbsolutePath().normalize();
            final Path r = root.toAbsolutePath().normalize();
            if (!abs.startsWith(r)) {
                refused("the program must be inside " + r + ": not taken");
            } else {
                program.setText(r.relativize(abs).toString().replace('\\', '/'));
            }
        }));
        openProgram.addActionListener(e -> programFile().ifPresent(actions::open));
        openLog.addActionListener(e -> logFile().ifPresent(actions::open));
        env.setLineWrap(false);

        for (final JLabel fact : List.of(state, last, next)) {
            fact.setForeground(Theme.MUTED_STRONG);
        }
        form.fact("State", state, "How the scheduler holds the job now");
        form.fact("Last run", last, "When the last run started and its exit code; ✗ when it failed");
        form.fact("Next run", next, "When the scheduler starts it next");
        form.row("Name", name, name, KitJob.NAME_FIELD, "Name the job: lower case, digits and -; unique",
                "Lower case, digits and -; unique in the kit",
                "The job's name in comando-kit.json. kit.py names the scheduler's entry after it, and the runner's "
                        + "files in .kit-jobs/: <name>.pid while it runs, <name>.tsv for its runs, <name>.log by "
                        + "default. "
                        + "Renaming makes it a new job for the scheduler: Save installs it under the new name.");
        form.row("Description", description, description, KitJob.DESCRIPTION_FIELD,
                "Say in one line what the job does, at most 80 characters",
                "One line on what it does, at most 80 characters",
                "A line for people reading the list, kept in comando-kit.json; it changes nothing in how the job "
                        + "runs.");
        form.row("Runs", runs, when, KitJob.DAYS_FIELD, "Pick the days it runs on and the time it starts",
                "Days and start time, local time; all seven days: daily",
                "The scheduler starts the job on each day picked, at the time on the right, in local time, 24 hours. "
                        + "kit.py never lets two runs of one job overlap: a start while the last run still runs is "
                        + "skipped. A service has no days or time: it is kept running and restarted when it exits.");
        form.input(KitJob.TIME_FIELD, time, "Time");
        form.row("Program", FormRows.withButton(program, chooseProgram, openProgram), program,
                KitJob.COMMAND_FIELD, "Set the program the job runs, a path inside the kit",
                "A path inside the kit, such as tools/kb-drift/kb-drift",
                "What the job runs. The scheduler does not start it directly: it starts kit.py run <name>, which "
                        + "starts this program with the arguments and environment below, records the run in "
                        + ".kit-jobs/<name>.tsv and sends its output to the log. The open button shows the program in "
                        + "an editor panel to read or change it; … picks another one from the kit's tools/.");
        form.row("Arguments", arguments, arguments, "arguments", "Give the program's arguments, separated by spaces",
                "Separated by spaces; quote one that holds a space",
                "The words passed to the program after its path. Quote a word that holds a space. {scheduler} "
                        + "anywhere becomes the scheduler's name: launchd, systemd or schtasks.");
        form.row("Environment", new JScrollPane(env), env, KitJob.ENV_FIELD,
                "Add variables for the program, one NAME=value per line",
                "One NAME=value per line; {scheduler} becomes launchd, systemd or schtasks",
                "Variables added to the program's environment, on top of the user's own, one NAME=value per line. "
                        + "{scheduler} in a value becomes the scheduler's name, so the program can tell a scheduled "
                        + "run from one started by hand.");
        form.row("Log", FormRows.withButton(log, openLog), log, KitJob.LOG_FIELD,
                "Set the file its output goes to; empty keeps the default",
                "Inside the kit; empty: the default shown in grey",
                "The file the program's output goes to, a path inside the kit. Left empty, kit.py writes to its "
                        + "default, shown in grey in the field. The open button shows the log in an editor panel.");
        form.row("Enabled", enabled, enabled, "enabled", "Turn off to pause the job without removing it",
                "",
                "On, the scheduler runs the job at its times. Off pauses it: the job stays in the list and in "
                        + "comando-kit.json, and Save unloads it from the scheduler until it is turned on again.");
        form.footer(new JLabel(" "), "Note");

        FormRows.onEdit(this::store, List.of(name, description, time, program, arguments, env, log));
        enabled.addActionListener(e -> store());
        final JPanel side = new JPanel(new BorderLayout(0, Theme.GAP));
        side.add(context(), BorderLayout.NORTH);
        side.add(form.panel(), BorderLayout.CENTER);
        FormRows.lay(body, left, side);
    }

    /** The two muted lines above the form: how a job runs, and where its actions are. */
    private static JComponent context() {
        final JPanel lines = new JPanel(new BorderLayout());
        final JLabel how = new JLabel(CONTEXT);
        how.setName("context");
        final JLabel where = new JLabel(CONTEXT_ACTIONS);
        where.setName("context-actions");
        for (final JLabel l : List.of(how, where)) {
            l.setForeground(Theme.MUTED);
        }
        lines.add(how, BorderLayout.NORTH);
        lines.add(where, BorderLayout.SOUTH);
        lines.setBorder(new EmptyBorder(0, 0, Theme.GAP, 0));
        return lines;
    }

    /** Shows whether day toggle {@code t} is on: the selection's colour and bold, else the toolkit's look. */
    private static void marked(final JToggleButton t) {
        t.setBackground(t.isSelected() ? Theme.SELECTION : null);
        t.setFont(t.getFont().deriveFont(t.isSelected() ? Font.BOLD : Font.PLAIN));
    }

    /**
     * The selected job's program, resolved in the kit, when its field names one. Whether the file exists is left to
     * the editor that opens it, as for the row menu's Open the log: no file is looked at on the EDT.
     */
    Optional<Path> programFile() {
        final Row r = list.getSelectedValue();
        return r == null ? Optional.empty() : resolved(r.program);
    }

    /** The selected job's log: the path in its field, else the one {@code kit.py} reported for the job as saved. */
    Optional<Path> logFile() {
        final Row r = list.getSelectedValue();
        if (r == null) {
            return Optional.empty();
        }
        if (!r.log.isBlank()) {
            return resolved(r.log);
        }
        final KitJobs.Entry e = facts.get(r.was);
        return e == null || e.logPath().isEmpty() ? Optional.empty() : resolved(e.logPath());
    }

    /** {@code path} resolved against the kit; empty when blank or not a path. */
    private Optional<Path> resolved(final String path) {
        if (path.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(root.resolve(path.strip()));
        } catch (final InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** The default log of the selected job as {@code kit.py} reported it, inside the kit when it is; empty if none. */
    private String defaultLog(final Row r) {
        final KitJobs.Entry e = facts.get(r.was);
        if (e == null || e.logPath().isEmpty()) {
            return "";
        }
        try {
            final Path f = Path.of(e.logPath()).toAbsolutePath().normalize();
            final Path k = root.toAbsolutePath().normalize();
            return f.startsWith(k) ? k.relativize(f).toString().replace('\\', '/') : f.toString();
        } catch (final InvalidPathException x) {
            return e.logPath();
        }
    }

    /** Fills the fields with the selected job's; with none selected they are empty and off. */
    private void load() {
        final Row r = list.getSelectedValue();
        loading = true;
        try {
            final boolean on = r != null;
            for (final JComponent c : List.of(name, description, time, program, chooseProgram, arguments, env, log, enabled, remove)) {
                c.setEnabled(on);
            }
            for (int d = 0; d < 7; d++) {
                days[d].setEnabled(on);
                days[d].setSelected(on && r.days[d]);
                marked(days[d]);
            }
            ((CardLayout) runs.getLayout()).show(runs, on && r.service ? "always" : "days");
            final String fallback = on ? defaultLog(r) : "";
            log.putClientProperty("JTextField.placeholderText", fallback.isEmpty() ? null : fallback);
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
        final Row r = list.getSelectedValue();
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

    private void edited(final boolean byUser) {
        if (byUser) {
            refused = "";
        }
        refresh();
        changed.run();
    }

    /** Shows the selected job's problems and state, marks the list, and enables what applies. */
    private void refresh() {
        final int i = list.getSelectedIndex();
        form.show(i < 0 ? Map.of() : problems(i), why -> true, refused, "");
        final List<String> status = i < 0 ? List.of(" ", " ", " ") : stateOf(rows.get(i));
        state.setText(Plain.text(status.get(0)));
        last.setText(Plain.text(status.get(1)));
        next.setText(Plain.text(status.get(2)));
        last.setForeground(status.get(1).endsWith("✗") ? Theme.DANGER : Theme.MUTED_STRONG);
        openProgram.setEnabled(programFile().isPresent());
        openLog.setEnabled(logFile().isPresent());
        list.repaint();
        changed.run();
    }

    /** For tests: the problems of the selected job as the form shows them, in field order. */
    Map<String, String> problemsShown() {
        final int i = list.getSelectedIndex();
        return i < 0 ? Map.of() : new LinkedHashMap<>(problems(i));
    }

    /**
     * A job's row: a mark for how it stands, its name, and its schedule muted at the end; plain labels, never HTML,
     * as the sidebar's rows. The mark: a warning for fields that fail, a cross for a failed last run, a tick for a
     * good one, play while it runs, stop when paused, an hourglass for a job with no run yet.
     */
    private final class Cell extends JPanel implements ListCellRenderer<Row> {
        private final JLabel title = new JLabel();
        private final JLabel schedule = new JLabel();

        Cell() {
            super(new BorderLayout(Theme.GAP, 0));
            setBorder(new EmptyBorder(2, 4, 2, 4));
            add(title, BorderLayout.CENTER);
            add(schedule, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends Row> l, final Row r, final int i,
                                                      final boolean sel, final boolean focus) {
            final Map<String, String> bad = i < rows.size() ? problems(i) : Map.of();
            final KitJobs.Entry e = facts.get(r.was);
            final boolean saved = e != null && r.was.equals(r.name.strip());
            final String mark;
            final String tip;
            if (!bad.isEmpty()) {
                mark = "warning";
                tip = "Its fields need fixing, as the line under the form says";
            } else if (!saved) {
                mark = "hourglass";
                tip = "New: Save installs it";
            } else if (!r.enabled) {
                mark = "stop";
                tip = "Paused: turn Enabled on to run it again";
            } else if (e.running()) {
                mark = "play";
                tip = "Running now";
            } else if (e.failed()) {
                mark = "error";
                tip = "Last run failed with exit " + e.lastExit() + ", " + when(e.lastStart());
            } else if (!e.lastStart().isEmpty()) {
                mark = "check";
                tip = "Last run ended well, " + when(e.lastStart());
            } else {
                mark = "hourglass";
                tip = "No run recorded yet";
            }
            title.setIcon(Icons.get(mark));
            title.setText(Plain.text(r.name.isBlank() ? "(no name)" : r.name));
            schedule.setText(Plain.text(r.service ? "always on" : r.enabled ? r.job().when() : "paused"));
            setToolTipText(tip + "; right-click for actions");
            setOpaque(true);
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            final boolean red = !sel && (!bad.isEmpty() || saved && e.failed());
            title.setForeground(sel ? l.getSelectionForeground()
                    : red ? Theme.DANGER : r.enabled ? l.getForeground() : Theme.MUTED_STRONG);
            schedule.setForeground(sel ? l.getSelectionForeground() : Theme.MUTED);
            title.setFont(l.getFont());
            schedule.setFont(l.getFont());
            return this;
        }
    }
}
