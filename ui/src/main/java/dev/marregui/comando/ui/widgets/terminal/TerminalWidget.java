package dev.marregui.comando.ui.widgets.terminal;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.StyledTextConsumer;
import com.jediterm.terminal.SubstringFinder;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.SubCharBuffer;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A terminal: the user's login shell on a pseudo-terminal, shown by JediTerm. For what Claude does not
 * do: interactive logins, git by hand, ssh. The shell starts on the first {@link #start}; until then the
 * area says where it will open and a click starts it. When it exits, a bar offers a restart in the current
 * directory: with a {@linkplain #setDirectorySource directory source}, the one the source names when the bar
 * shows and when Restart is clicked. Text sent to a shell that has just started waits until the shell is
 * ready; text still waiting when the shell is closed is dropped.
 */
public final class TerminalWidget implements Zoomable, Searchable {

    private static final System.Logger LOG = System.getLogger(TerminalWidget.class.getName());
    private static final int GATE_POLL_MILLIS = 50;
    /** The job reported when {@code ps} cannot say what the shell runs: closing asks rather than kills unseen. */
    public static final String UNCHECKED_JOB = "a program comando could not check";
    /** Names of shells: a pty whose process carries another name has been replaced by a program ({@code exec ssh host}). */
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "fish", "dash", "ksh", "mksh",
            "tcsh", "csh", "nu", "xonsh", "elvish", "pwsh", "login");

    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel exitedBar = new JPanel(new BorderLayout(Theme.GAP, 0));
    private final JLabel exitedLabel = Plain.only(new JLabel());
    private final JButton restartButton = Plain.only(new JButton());
    private final JLabel empty = Plain.only(new JLabel());
    private final InputGate gate = new InputGate(this::write);
    private final Timer gatePoll = new Timer(GATE_POLL_MILLIS, e -> pollGate());
    private final List<String> command;
    private Path directory;
    private Supplier<Path> directorySource;
    private Term term;
    private Pty pty;
    private float fontSize = Theme.mono().getSize2D();
    private String findText = "";
    /** Set by the emulator on any change of the buffer, cleared by a find that searches it again. */
    private volatile boolean bufferChanged;

    public TerminalWidget(Path directory) {
        this(directory, Pty.loginShell());
    }

    /** A terminal running {@code command} instead of the login shell. */
    public TerminalWidget(Path directory, List<String> command) {
        this.directory = directory;
        this.command = List.copyOf(command);
        exitedBar.setBorder(BorderFactory.createEmptyBorder(4, Theme.PAD, 4, Theme.PAD));
        restartButton.addActionListener(e -> restart());
        exitedLabel.setForeground(Theme.MUTED);
        exitedBar.add(exitedLabel, BorderLayout.CENTER);
        exitedBar.add(restartButton, BorderLayout.EAST);
        exitedBar.setVisible(false);
        root.add(exitedBar, BorderLayout.NORTH);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setForeground(Theme.MUTED);
        empty.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        empty.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                start();
                focus();
            }
        });
        root.add(empty, BorderLayout.CENTER);
        gatePoll.setRepeats(true);
        showDirectory();
        Capabilities.mark(root, this);
    }

    public JComponent component() {
        return root;
    }

    /** The keys a shell reads with Ctrl: the letters, {@code [ \ ]} and space ({@code ^A}…{@code ^Z}, {@code ^[ ^\ ^]}, {@code ^@}). */
    static final int MODIFIERS = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
            | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;

    /**
     * True when {@code key}, pressed in {@code c}, belongs to the shell of a terminal that holds {@code c}: Ctrl alone
     * with a letter, {@code [}, {@code \}, {@code ]} or space, the control characters a shell reads. On macOS no
     * command uses them; elsewhere Ctrl is ⌘'s key, and in a terminal the shell keeps it, as it keeps ⌃ on macOS. On
     * macOS the terminal also keeps ⌘K, its Clear Buffer, as macOS terminals do.
     */
    public static boolean keepsKey(Component c, KeyStroke key) {
        boolean inTerminal = c != null && (c instanceof JediTermWidget || SwingUtilities.getAncestorOfClass(JediTermWidget.class, c) != null);
        int code = key.getKeyCode();
        if ((key.getModifiers() & MODIFIERS) == InputEvent.META_DOWN_MASK && code == KeyEvent.VK_K && Platform.isMac()) {
            return inTerminal;
        }
        if ((key.getModifiers() & MODIFIERS) != InputEvent.CTRL_DOWN_MASK) {
            return false;
        }
        boolean control = code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z || code == KeyEvent.VK_OPEN_BRACKET
                || code == KeyEvent.VK_BACK_SLASH || code == KeyEvent.VK_CLOSE_BRACKET || code == KeyEvent.VK_SPACE;
        return control && inTerminal;
    }

    public boolean isStarted() {
        return term != null;
    }

    public boolean isRunning() {
        return pty != null && pty.process().isAlive();
    }

    /** Loads the pseudo-terminal's native code ahead of the first terminal; call off the EDT. */
    public static void preload() {
        Pty.preload();
    }

    /** Starts the shell when it is not running; on the EDT. */
    public void start() {
        if (isRunning()) {
            return;
        }
        removeTerm();
        Pty started;
        try {
            started = Pty.start(command, directory, 120, 30);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot start a shell in " + directory + ": " + e.getMessage());
            exitedLabel.setText("Cannot start a shell in " + Labels.shortPath(directory.toString()) + ": " + e.getMessage());
            restartButton.setText("Try again");
            exitedBar.setVisible(true);
            root.revalidate();
            return;
        }
        pty = started;
        term = new Term(new Settings(() -> fontSize));
        // the palette is read at each paint; another tint or ground repaints it (record 101)
        Term shown = term;
        SidebarLook.whileShown(shown, shown::repaint);
        term.getTerminalTextBuffer().addModelListener(() -> bufferChanged = true);
        term.setTtyConnector(started);
        term.start();
        gate.started(System.nanoTime());
        exitedBar.setVisible(false);
        root.remove(empty);
        root.add(term, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        if (!gate.isOpen()) {
            gatePoll.start();
        }
        UiErrands.start("terminal-exit", () -> {
            try {
                int code = started.process().waitFor();
                Edt.later(() -> exited(started, code));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void exited(Pty which, int code) {
        if (which != pty) {
            return;
        }
        showExited(code);
    }

    void showExited(int code) {
        followSource();
        exitedLabel.setText("The shell exited with code " + code);
        restartButton.setText(restartText());
        exitedBar.setVisible(true);
        root.revalidate();
    }

    /** Ends the shell and starts a new one in the current directory, the source's when there is one. */
    public void restart() {
        close();
        followSource();
        start();
    }

    /**
     * Where the exit bar and its Restart read the directory, at the moment the bar shows and the moment Restart is
     * clicked: the directory of the panel's session then, not when the shell started.
     */
    public void setDirectorySource(Supplier<Path> source) {
        this.directorySource = source;
    }

    private void followSource() {
        Supplier<Path> source = directorySource;
        Path now = source == null ? null : source.get();
        if (now != null) {
            setDirectory(now);
        }
    }

    /** Where the next start opens the shell. */
    public void setDirectory(Path directory) {
        if (!directory.equals(this.directory)) {
            this.directory = directory;
            showDirectory();
        }
    }

    public Path directory() {
        return directory;
    }

    private void showDirectory() {
        empty.setText("Shell in " + Labels.shortPath(directory.toString()) + KeyText.of(" — click or press ⌃3 to start"));
        if (exitedBar.isVisible() && isStarted()) {
            restartButton.setText(restartText());
        }
    }

    private String restartText() {
        return "Restart in " + Labels.shortPath(directory.toString());
    }

    String emptyText() {
        return empty.getText();
    }

    String restartButtonText() {
        return restartButton.getText();
    }

    /**
     * Types {@code text} into the shell, as if pasted. A shell that is not running is started; text sent
     * before it is ready (its first output followed by a short quiet, at most a few seconds) waits and is
     * written in order once it is. Never blocks the EDT.
     */
    public void send(String text) {
        if (!isRunning()) {
            start();
        }
        Pty p = pty;
        if (p == null) {
            return;
        }
        if (gate.send(text, System.nanoTime(), p.lastOutputNanos())) {
            gatePoll.start();
        }
    }

    private void pollGate() {
        Pty p = pty;
        if (p == null || !gate.poll(System.nanoTime(), p.lastOutputNanos())) {
            gatePoll.stop();
        }
    }

    private void write(String text) {
        Pty p = pty;
        if (p == null) {
            return;
        }
        try {
            p.write(text);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write to the shell: " + e.getMessage());
        }
    }

    /**
     * Whether the shell runs a job in the foreground: a build, a server, an ssh session. Background helpers the
     * shell starts (a prompt's git daemon, direnv) do not count. Runs {@code ps} once, a few milliseconds; call it
     * on a user action, not on every render.
     */
    public boolean hasRunningJob() {
        return runningJob().isPresent();
    }

    /** The foreground job's command name when the shell runs one, else empty; see {@link #hasRunningJob}. */
    public Optional<String> runningJob() {
        Pty p = pty;
        if (p == null || !p.process().isAlive()) {
            return Optional.empty();
        }
        try {
            return foregroundJob(p.process().pid(), command.getFirst());
        } catch (UnsupportedOperationException e) {
            return Optional.of(UNCHECKED_JOB);
        }
    }

    /** {@link #foregroundJob(long, String, Function)} with the system's {@code ps}. */
    static Optional<String> foregroundJob(long shellPid, String shell) {
        return foregroundJob(shellPid, shell, TerminalWidget::ps);
    }

    /**
     * What the terminal {@code shellPid} leads runs in front, else empty. Fails closed: when {@code ps} fails,
     * times out or prints what cannot be read, the answer is {@link #UNCHECKED_JOB}, so closing asks.
     * <ul>
     *   <li>The pty's process no longer carries the name of a shell (the shell ran {@code exec ssh host}): that
     *       program's name.</li>
     *   <li>The terminal's foreground process group differs from the shell's own: the job's command name.</li>
     *   <li>Otherwise the shell is in front: empty.</li>
     * </ul>
     * With job control off ({@code set +m}, or a shell started without it) a job runs in the shell's own process
     * group, so the two groups match and this check cannot see the job: it reports none.
     *
     * @param shell the command the pty started, as a path or a name
     * @param ps    runs {@code ps} with its arguments; returns its non-blank lines, or none when it failed
     */
    static Optional<String> foregroundJob(long shellPid, String shell, Function<List<String>, List<String>> ps) {
        List<String> self = ps.apply(List.of("-o", "tpgid=,pgid=,comm=", "-p", Long.toString(shellPid)));
        if (self.isEmpty()) {
            return Optional.of(UNCHECKED_JOB);
        }
        String[] ids = self.getFirst().strip().split("\\s+", 3);
        if (ids.length < 3) {
            return Optional.of(UNCHECKED_JOB);
        }
        String running = programName(ids[2]);
        if (!running.equals(programName(shell)) && !SHELLS.contains(running)) {
            return Optional.of(running);
        }
        if (ids[0].equals(ids[1]) || ids[0].startsWith("-") || ids[0].equals("0")) {
            return Optional.empty();
        }
        String foreground = ids[0];
        String pids = ProcessHandle.of(shellPid).map(h -> h.descendants().map(d -> Long.toString(d.pid()))
                .collect(Collectors.joining(","))).orElse("");
        if (pids.isEmpty()) {
            return Optional.of("a program");
        }
        for (String line : ps.apply(List.of("-o", "pgid=,comm=", "-p", pids))) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equals(foreground)) {
                return Optional.of(programName(parts[1]));
            }
        }
        return Optional.of("a program");
    }

    /** {@code /bin/zsh}, {@code -zsh} (a login shell) and {@code zsh} all name {@code zsh}. */
    static String programName(String comm) {
        String c = comm.strip();
        int slash = c.lastIndexOf('/');
        c = slash < 0 ? c : c.substring(slash + 1);
        return c.startsWith("-") ? c.substring(1) : c;
    }

    /** The lines {@code ps} prints for {@code args}, or none when it fails or takes over a second. */
    private static List<String> ps(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("ps");
        command.addAll(args);
        try {
            Process ps = new ProcessBuilder(command).redirectErrorStream(true).start();
            ps.getOutputStream().close();
            if (!ps.waitFor(1, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
                return List.of();
            }
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return out.lines().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    public void focus() {
        if (term != null) {
            term.requestFocusInWindow();
        }
    }

    /** Ends the shell and frees the emulator. */
    public void close() {
        gatePoll.stop();
        gate.clear(); // never typed into the next shell, which may open elsewhere
        Pty p = pty;
        pty = null;
        removeTerm();
        exitedBar.setVisible(false);
        if (p != null && p.process().isAlive()) {
            p.process().destroy();
        }
    }

    private void removeTerm() {
        if (term != null) {
            root.remove(term);
            term.close();
            term = null;
            findText = "";
            root.add(empty, BorderLayout.CENTER);
            root.revalidate();
            root.repaint();
        }
    }

    List<String> command() {
        return command;
    }

    /** How many texts wait for the shell, for tests. */
    int inputWaiting() {
        return gate.waiting();
    }

    /** The screen's text, for tests. */
    String screenText() {
        return term == null ? "" : term.getTerminalTextBuffer().getScreenLines();
    }

    /** The emulator's buffer, for tests; null before the shell starts. */
    TerminalTextBuffer buffer() {
        return term == null ? null : term.getTerminalTextBuffer();
    }

    // ---- zoom

    @Override
    public void zoom(int steps) {
        setFontSize(fontSize + steps);
    }

    @Override
    public void resetZoom() {
        setFontSize(Theme.mono().getSize2D());
    }

    float fontSize() {
        return fontSize;
    }

    private void setFontSize(float size) {
        float s = Zoomable.clampSize(size);
        if (s == fontSize) {
            return;
        }
        fontSize = s;
        if (term != null) {
            term.refont();
        }
    }

    // ---- find

    /** Finds {@code text} in the scrollback and the screen, ignoring case; the first find selects the latest match. */
    @Override
    public int find(String text, boolean forward) {
        Term t = term;
        if (t == null || text.isEmpty()) {
            clearFind();
            return 0;
        }
        TerminalPanel panel = t.getTerminalPanel();
        TerminalTextBuffer buffer = t.getTerminalTextBuffer();
        SubstringFinder.FindResult result;
        SubstringFinder.FindResult old = panel.getFindResult();
        // output that changed the buffer moves or replaces lines, so earlier results point at other text
        if (!text.equals(findText) || bufferChanged || old == null) {
            // the same text again after new output: step on from the match the user was on, when it is still there
            Point at = text.equals(findText) && old != null && !old.getItems().isEmpty() ? old.selectedItem().getStart() : null;
            findText = text;
            bufferChanged = false; // before the search: a change during it searches again next time
            SubstringFinder.FindResult found = search(buffer, text);
            panel.setFindResult(found);
            if (found.getItems().isEmpty()) {
                return 0;
            }
            if (at != null && selectAt(found, at)) {
                result = forward ? panel.selectNextFindResultItem() : panel.selectPrevFindResultItem();
            } else {
                result = panel.selectPrevFindResultItem(); // from the first item, "previous" is the last: the latest output
            }
        } else {
            result = forward ? panel.selectNextFindResultItem() : panel.selectPrevFindResultItem();
        }
        return result == null ? 0 : result.getItems().size();
    }

    @Override
    public void clearFind() {
        findText = "";
        if (term != null) {
            term.getTerminalPanel().setFindResult(null);
        }
    }

    /** Selects the item of {@code result} that starts at {@code at}; false, with the first selected, when none does. */
    private static boolean selectAt(SubstringFinder.FindResult result, Point at) {
        for (int i = 0; i < result.getItems().size(); i++) {
            if (result.selectedItem().getStart().equals(at)) {
                return true;
            }
            result.nextFindItem();
        }
        return false;
    }

    private static SubstringFinder.FindResult search(TerminalTextBuffer buffer, String text) {
        SubstringFinder finder = new SubstringFinder(text, true);
        buffer.lock();
        try {
            buffer.processHistoryAndScreenLines(-buffer.getHistoryLinesCount(), -1, new StyledTextConsumer() {
                @Override
                public void consume(int x, int y, TextStyle style, CharBuffer characters, int startRow) {
                    CharBuffer chars = characters;
                    int offset = 0;
                    int length = characters.length();
                    if (characters instanceof SubCharBuffer sub) {
                        chars = sub.getParent();
                        offset = sub.getOffset();
                    }
                    for (int i = offset; i < offset + length; i++) {
                        finder.nextChar(x, y - startRow, chars, i);
                    }
                }

                @Override
                public void consumeNul(int x, int y, int nulIndex, TextStyle style, CharBuffer characters, int startRow) {
                    // blank cells match nothing
                }

                @Override
                public void consumeQueue(int x, int y, int nulIndex, int startRow) {
                    // end of a line
                }
            });
        } finally {
            buffer.unlock();
        }
        return finder.getResult();
    }

    /** The emulator, with a panel whose font can be changed after it was built. */
    private static final class Term extends JediTermWidget {

        Term(SettingsProvider settings) {
            super(120, 30, settings);
        }

        @Override
        protected TerminalPanel createTerminalPanel(SettingsProvider settings, StyleState style, TerminalTextBuffer buffer) {
            return new Panel(settings, buffer, style);
        }

        void refont() {
            ((Panel) getTerminalPanel()).refont();
        }
    }

    private static final class Panel extends TerminalPanel {

        Panel(SettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
            super(settings, buffer, style);
        }

        /** Reads the font size from the settings again and resizes the grid to the panel. */
        void refont() {
            reinitFontAndResize();
        }
    }

    /**
     * The terminal's sixteen colours, read from {@code Theme.content()} at each lookup; indices past 15, the 256-colour
     * cube and greys, are xterm's.
     */
    private static final ColorPalette PALETTE = new ColorPalette() {
        @Override
        protected com.jediterm.core.Color getForegroundByColorIndex(int i) {
            return core(Theme.content().ansi(i));
        }

        @Override
        protected com.jediterm.core.Color getBackgroundByColorIndex(int i) {
            return core(Theme.content().ansi(i));
        }

        private static com.jediterm.core.Color core(Color c) {
            return new com.jediterm.core.Color(c.getRGB() & 0xFFFFFF);
        }
    };

    /** Colours from the theme; the font is the theme's monospaced font at the terminal's own size. */
    static final class Settings extends DefaultSettingsProvider {

        private final DoubleSupplier size;

        Settings(DoubleSupplier size) {
            this.size = size;
        }

        @Override
        public Font getTerminalFont() {
            return Theme.mono().deriveFont((float) size.getAsDouble());
        }

        @Override
        public float getTerminalFontSize() {
            return (float) size.getAsDouble();
        }

        @Override
        public TerminalColor getDefaultForeground() {
            Color ink = Theme.content().ink;
            return TerminalColor.rgb(ink.getRed(), ink.getGreen(), ink.getBlue());
        }

        /** The sixteen colours of the content palette chosen now (record 101). */
        @Override
        public ColorPalette getTerminalColorPalette() {
            return PALETTE;
        }

        @Override
        public TerminalColor getDefaultBackground() {
            // the panels' ground, set once in Theme (record 98)
            Color g = Theme.ground();
            return TerminalColor.rgb(g.getRed(), g.getGreen(), g.getBlue());
        }

        /** Deprecated in JediTerm, yet still read for text written before the first paint. */
        @Override
        @SuppressWarnings("deprecation")
        public TextStyle getDefaultStyle() {
            return new TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        /**
         * JediTerm binds no key to Select All; on macOS ⌘A selects the buffer, as the Edit menu says. Elsewhere Ctrl+A
         * stays the shell's start of line.
         */
        @Override
        public TerminalActionPresentation getSelectAllActionPresentation() {
            return Platform.macKeys() ? new TerminalActionPresentation("Select All",
                    KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.META_DOWN_MASK))
                    : super.getSelectAllActionPresentation();
        }

        @Override
        public boolean audibleBell() {
            return false;
        }

        @Override
        public int getBufferMaxLinesCount() {
            return 20_000;
        }
    }
}
