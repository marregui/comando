package dev.marregui.comando.app;

import com.jediterm.terminal.ui.JediTermWidget;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingPorts;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.shell.ComandoUi;
import dev.marregui.comando.ui.shell.Setup;
import org.awaitility.Awaitility;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keyboard in a real window, with macOS's keys and with the other desktops', so CI on Linux checks that comando
 * there works as it does on a Mac: each key is macOS's with Ctrl for ⌘, but for the few that would be another
 * command's; no two commands share a key; in the terminal Ctrl with a letter reaches the shell, as ⌃ does on macOS;
 * the editor's run keys run, and every text that names a key names this desktop's. Either set of keys is chosen with
 * {@link Platform#KEYS_PROPERTY}, so both run on any desktop. Needs a display: {@code ./gradlew :app:uiTest} (on
 * Linux under {@code xvfb-run}).
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class UiKeysTest {

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int META = InputEvent.META_DOWN_MASK;
    private static final long WAIT_MILLIS = 15_000;

    /**
     * The keys that are not macOS's with Ctrl for ⌘ on the other desktops, and why: there Ctrl with a digit is a panel
     * (⌘1…⌘9), so the lower tabs (⌃1–⌃4) take Alt; Ctrl+⏎ is Run and Alt+⏎ Run as SQL, so the line to the terminal
     * (⌃⏎) takes Ctrl+Alt+⏎.
     */
    private static final Map<String, KeyStroke> MOVED = Map.of(
            "terminal.run", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, CTRL | ALT),
            "view.session", KeyStroke.getKeyStroke(KeyEvent.VK_1, ALT),
            "view.approvals", KeyStroke.getKeyStroke(KeyEvent.VK_2, ALT),
            "view.terminal", KeyStroke.getKeyStroke(KeyEvent.VK_3, ALT),
            "view.results", KeyStroke.getKeyStroke(KeyEvent.VK_4, ALT));

    private static final class Texts implements PanelTexts {
        private final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public String load(PanelId panel) {
            return map.getOrDefault(panel.value(), "");
        }

        @Override
        public void changed(PanelId panel, String text) {
            map.put(panel.value(), text);
        }

        @Override
        public void forget(PanelId panel) {
            map.remove(panel.value());
        }
    }

    /** Effects go to the router once the shell exists, as in the app. */
    private static final class Deferred implements StateLoop.EffectHandler {
        volatile StateLoop.EffectHandler target;

        @Override
        public void handle(Effect effect) {
            if (target != null) {
                target.handle(effect);
            }
        }
    }

    /** A started window with one panel, and the intents it sent. */
    private record Window(StateLoop loop, ComandoUi ui, List<Intent> intents, JFrame frame) implements AutoCloseable {
        @Override
        public void close() {
            ui.close();
            loop.close();
        }
    }

    /** Window layouts saved here, apart from the replay test's node, whose window expects the default layout. */
    private static final String PREFS = "dev/marregui/comando-test-keys";

    private final List<AutoCloseable> open = new ArrayList<>();
    private final String keys = System.getProperty(Platform.KEYS_PROPERTY);
    private final String prefs = System.getProperty("comando.prefsNode");

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
        restore(Platform.KEYS_PROPERTY, keys);
        restore("comando.prefsNode", prefs);
        Preferences.userRoot().node(PREFS).removeNode();
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private Window open(String keys) throws Exception {
        System.setProperty(Platform.KEYS_PROPERTY, keys);
        System.setProperty("comando.prefsNode", PREFS);
        IntentLog.Replay replay = IntentLog.read(Path.of(getClass().getResource("/replay/snapshot.jsonl").toURI()));
        RecordingPorts ports = new RecordingPorts();
        Deferred effects = new Deferred();
        StateLoop loop = new StateLoop(replay.initial(), new Reducer(Clock.SYSTEM), effects);
        List<Intent> sent = new CopyOnWriteArrayList<>();
        IntentSink recording = i -> {
            sent.add(i);
            loop.dispatch(i);
        };
        ComandoUi ui = new ComandoUi(new ComandoUi.Dependencies(recording, loop::state, loop, new TranscriptRegistry(), new ResultRegistry(),
                new ConsoleRegistry(), new SchemaRegistry(), new Texts(), List::of, null, Setup.NONE, "keys", null, null, () -> { }));
        effects.target = new EffectRouter(ports, ports, ports, ui, ports, ports);
        open.add(() -> {
            ui.close();
            loop.close();
        });
        List<Frame> before = List.of(Frame.getFrames());
        loop.start();
        ui.start();
        // the frame this start opened; an earlier test's may not be gone yet
        JFrame frame = onEdt(() -> (JFrame) Stream.of(Frame.getFrames())
                .filter(f -> f instanceof JFrame && f.isShowing() && !before.contains(f)).findFirst().orElseThrow());
        return new Window(loop, ui, sent, frame);
    }

    private static Map<String, KeyStroke> keys(Window w) {
        Map<String, KeyStroke> keys = new LinkedHashMap<>();
        for (Command c : w.ui().commands()) {
            if (c.key() != null) {
                keys.put(c.id(), c.key());
            }
        }
        return keys;
    }

    // ---- the key map

    @Test
    void theOtherDesktopsKeysAreMacOssWithCtrlForCmdButForTheMovedOnesAndNoTwoCommandsShareAKey() throws Exception {
        Map<String, KeyStroke> mac;
        try (Window w = open("mac")) {
            mac = keys(w);
        }
        open.clear();
        Map<String, KeyStroke> other;
        try (Window w = open("linux")) {
            other = keys(w);
        }
        open.clear();
        assertThat(sharedKeys(mac)).as("mac keys").isEmpty();
        assertThat(sharedKeys(other)).as("linux keys").isEmpty();
        // on macOS the application menu owns ⌘Q; elsewhere Quit has a key of its own
        KeyStroke quit = other.remove("app.quit");
        mac.remove("app.quit");
        if (!Platform.isMac()) {
            assertThat(quit).isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_Q, CTRL));
        }

        assertThat(other.keySet()).as("the same commands have keys").containsExactlyInAnyOrderElementsOf(mac.keySet());
        List<String> differ = new ArrayList<>();
        for (Map.Entry<String, KeyStroke> e : mac.entrySet()) {
            KeyStroke expected = MOVED.getOrDefault(e.getKey(), withCtrlForCmd(e.getValue()));
            if (!expected.equals(other.get(e.getKey()))) {
                differ.add(e.getKey() + ": macOS " + e.getValue() + ", expected " + expected + ", is " + other.get(e.getKey()));
            }
        }
        assertThat(differ).isEmpty();
        for (String id : MOVED.keySet()) {
            assertThat(mac.get(id).getModifiers() & CTRL).as(id + " is a ⌃ key on macOS").isNotZero();
        }
    }

    /** The commands that share a key with another, grouped by key. */
    private static List<List<String>> sharedKeys(Map<String, KeyStroke> keys) {
        Map<KeyStroke, List<String>> byKey = keys.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, LinkedHashMap::new, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        return byKey.values().stream().filter(ids -> ids.size() > 1).toList();
    }

    // ---- the window

    @Test
    @EnabledIfSystemProperty(named = "comando.offScreen", matches = "true")
    void offScreenTheWindowIsOnNoScreenAndShowing() throws Exception {
        Window w = open("mac");
        Rectangle bounds = onEdt(w.frame()::getBounds);
        List<Rectangle> screens = Stream.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                .map(d -> d.getDefaultConfiguration().getBounds()).toList();
        assertThat(screens).noneMatch(bounds::intersects);
        assertThat(onEdt(w.frame()::isShowing)).as("a real window, painted and given keys").isTrue();
    }

    // ---- the terminal

    /** Ctrl with these letters is a command's key on the other desktops (⌘'s), and a shell's in a terminal. */
    private static final int[] SHELL_LETTERS = {KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_B, KeyEvent.VK_T, KeyEvent.VK_J,
            KeyEvent.VK_O, KeyEvent.VK_A, KeyEvent.VK_F, KeyEvent.VK_K, KeyEvent.VK_U, KeyEvent.VK_L, KeyEvent.VK_C};

    @ParameterizedTest
    @ValueSource(strings = {"mac", "linux"})
    void inTheTerminalCtrlWithALetterReachesTheShellAndTheCommandsKeysStillWork(String keys) throws Exception {
        Window w = open(keys);
        boolean mac = keys.equals("mac");
        focusEditor(w);
        press(w, KeyEvent.VK_3, mac ? CTRL : ALT); // the Terminal tab
        Component shell = await("the terminal has the keyboard", () -> inTerminal(focusOwner(w)) ? focusOwner(w) : null, WAIT_MILLIS);
        int panels = onEdt(() -> w.loop().state().panels().size());
        boolean sidebar = onEdt(() -> sidebarShown(w.frame()));
        // a dispatcher added after comando's sees a key only when comando's claimed none, so the terminal gets it
        List<Integer> reached = new CopyOnWriteArrayList<>();
        KeyEventDispatcher after = e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && inTerminal(e.getComponent())) {
                reached.add(e.getKeyCode());
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(after);
        try {
            for (int code : SHELL_LETTERS) {
                press(w, code, CTRL);
            }
            settle(w);
        } finally {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(after);
        }

        assertThat(reached).as("Ctrl with a letter reached the shell").containsExactlyElementsOf(boxed(SHELL_LETTERS));
        assertThat(onEdt(() -> w.loop().state().panels().size())).as("^W and ^T closed or opened no panel").isEqualTo(panels);
        assertThat(onEdt(() -> sidebarShown(w.frame()))).as("^B left the sidebar").isEqualTo(sidebar);
        assertThat(inTerminal(focusOwner(w))).as("^E and ^F left the keyboard in the terminal").isTrue();

        // the commands' own keys still work from the terminal: the Session tab, then back to the terminal
        press(w, KeyEvent.VK_1, mac ? CTRL : ALT);
        await("the Session tab", () -> w.loop().state().lowerTab().kind() == LowerTab.Kind.SESSION, WAIT_MILLIS);
    }

    // ---- the editor's run keys

    @ParameterizedTest
    @ValueSource(strings = {"mac", "linux"})
    void theRunKeyRunsTheBlockAndTheTerminalKeyTypesTheLineIntoTheShell(String keys) throws Exception {
        Window w = open(keys);
        boolean mac = keys.equals("mac");
        RSyntaxTextArea editor = focusEditor(w);
        String marker = "comando-keys-" + keys;
        SwingUtilities.invokeAndWait(() -> {
            editor.setText("echo " + marker);
            editor.setCaretPosition(0);
        });

        press(w, KeyEvent.VK_ENTER, mac ? META : CTRL); // Run
        await("Run sent the block", () -> w.intents().stream().anyMatch(i -> i instanceof Intent.RunText r && r.text().contains(marker)), WAIT_MILLIS);
        long runs = w.intents().stream().filter(i -> i instanceof Intent.RunText).count();

        focusEditor(w);
        press(w, KeyEvent.VK_ENTER, mac ? CTRL : CTRL | ALT); // Run in the terminal
        JediTermWidget term = await("a terminal", () -> find(w.frame(), JediTermWidget.class).orElse(null), WAIT_MILLIS);
        await("the line reached the shell", () -> screen(term).contains(marker), WAIT_MILLIS);
        assertThat(w.intents().stream().filter(i -> i instanceof Intent.RunText).count()).as("no second run").isEqualTo(runs);
    }

    // ---- the shortcuts table

    @ParameterizedTest
    @ValueSource(strings = {"mac", "linux"})
    void theShortcutsTableListsThisDesktopsKeys(String keys) throws Exception {
        Window w = open(keys);
        boolean mac = keys.equals("mac");
        focusEditor(w);
        press(w, KeyEvent.VK_SLASH, (mac ? META : CTRL) | InputEvent.SHIFT_DOWN_MASK); // Keyboard shortcuts

        String table = await("the shortcuts table", () -> w.loop().state().views().stream()
                .filter(v -> v.title().equals("Keyboard shortcuts") && v.spec() instanceof ViewSpec.Markdown)
                .map(v -> ((ViewSpec.Markdown) v.spec()).markdown()).findFirst().orElse(null), WAIT_MILLIS);
        if (mac) {
            assertThat(table).contains("| ⌘⏎ |", "| ⌃⏎ |", "| ⌃1 |", "| ⌘1 |", "| ⇧⌘P |").doesNotContain("Ctrl+");
        } else {
            assertThat(table).contains("| Ctrl+Enter |", "| Ctrl+Alt+Enter |", "| Alt+1 |", "| Ctrl+1 |", "| Ctrl+Shift+P |")
                    .doesNotContain("⌘", "⌃", "⌥");
        }
    }

    // ---- texts naming keys

    @ParameterizedTest
    @ValueSource(strings = {"mac", "linux"})
    void everyTextInTheWindowNamesThisDesktopsKeys(String keys) throws Exception {
        Window w = open(keys);
        settle(w);
        List<String> texts = onEdt(() -> {
            List<String> out = new ArrayList<>();
            texts(w.frame().getContentPane(), out);
            for (int i = 0; i < w.frame().getJMenuBar().getMenuCount(); i++) {
                texts(w.frame().getJMenuBar().getMenu(i), out);
            }
            return out;
        });
        List<String> named = texts.stream().filter(t -> t.contains("⌘") || t.contains("⌃") || t.contains("Ctrl+")).toList();
        assertThat(named).as("texts that name a key").isNotEmpty();
        if (keys.equals("linux")) {
            assertThat(named).as("macOS's glyphs on another desktop").noneMatch(t -> t.contains("⌘") || t.contains("⌃") || t.contains("⌥"));
        } else {
            assertThat(named).as("another desktop's names on macOS").noneMatch(t -> t.contains("Ctrl+"));
        }
    }

    /** Every tooltip is one short line: what the control does and one fact it does not show (live status aside). */
    @Test
    void everyTooltipInTheWindowIsOneShortLine() throws Exception {
        Window w = open("mac");
        settle(w);
        List<String> tips = onEdt(() -> {
            List<String> out = new ArrayList<>();
            tips(w.frame().getContentPane(), out);
            for (int i = 0; i < w.frame().getJMenuBar().getMenuCount(); i++) {
                tips(w.frame().getJMenuBar().getMenu(i), out);
            }
            return out;
        });
        assertThat(tips).as("tooltips found").hasSizeGreaterThan(20);
        assertThat(tips).as("one line of at most 80 characters")
                .allSatisfy(t -> assertThat(t).doesNotContain("\n", "<br>").hasSizeLessThanOrEqualTo(80));
    }

    private static void tips(Component c, List<String> out) {
        if (c instanceof JComponent j && j.getToolTipText() != null && !j.getToolTipText().isBlank()) {
            out.add(j.getToolTipText());
        }
        if (c instanceof JMenu m) {
            for (int i = 0; i < m.getItemCount(); i++) {
                JMenuItem item = m.getItem(i);
                if (item != null) {
                    tips(item, out);
                }
            }
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                tips(child, out);
            }
        }
    }

    private static void texts(Component c, List<String> out) {
        if (c instanceof JComponent j && j.getToolTipText() != null) {
            out.add(j.getToolTipText());
        }
        if (c instanceof JLabel l && l.getText() != null) {
            out.add(l.getText());
        }
        if (c instanceof AbstractButton b && b.getText() != null) {
            out.add(b.getText());
        }
        if (c instanceof JMenu m) {
            for (int i = 0; i < m.getItemCount(); i++) {
                JMenuItem item = m.getItem(i);
                if (item != null) {
                    texts(item, out);
                }
            }
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                texts(child, out);
            }
        }
    }

    // ---- helpers

    private static KeyStroke withCtrlForCmd(KeyStroke k) {
        int m = k.getModifiers() & (InputEvent.SHIFT_DOWN_MASK | CTRL | ALT | META);
        return KeyStroke.getKeyStroke(k.getKeyCode(), (m & META) != 0 ? (m & ~META) | CTRL : m);
    }

    private static List<Integer> boxed(int[] codes) {
        List<Integer> out = new ArrayList<>(codes.length);
        for (int c : codes) {
            out.add(c);
        }
        return out;
    }

    /** Gives the focused panel's editor the window's keyboard, as a click in it would. */
    private static RSyntaxTextArea focusEditor(Window w) throws Exception {
        RSyntaxTextArea editor = await("an editor", () -> find(w.frame(), RSyntaxTextArea.class).orElse(null), WAIT_MILLIS);
        SwingUtilities.invokeAndWait(editor::requestFocusInWindow);
        return await("the editor has the keyboard", () -> focusOwner(w) == editor ? editor : null, WAIT_MILLIS);
    }

    /** Presses {@code code} with {@code modifiers} in the component that has the keyboard, as a typist would. */
    private static void press(Window w, int code, int modifiers) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Component target = focusOwner(w);
            assertThat(target).as("a component has the keyboard").isNotNull();
            long now = System.currentTimeMillis();
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, code, KeyEvent.CHAR_UNDEFINED));
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, code, KeyEvent.CHAR_UNDEFINED));
        });
        settle(w);
    }

    /**
     * The component with the window's keyboard: its focus owner while the window is the desktop's active one, else the
     * one that gets the keyboard when it is. The tests never bring the process forward: on a desktop in use another
     * application takes the front back at any moment, which left the process without a focus owner mid-test. Keys go to
     * this component directly, and comando routes them by the component they are pressed in, so nothing here needs the
     * desktop's focus.
     */
    private static Component focusOwner(Window w) {
        return onEdtUnchecked(() -> w.frame().getMostRecentFocusOwner());
    }

    private static boolean inTerminal(Component c) {
        return c != null && (c instanceof JediTermWidget || SwingUtilities.getAncestorOfClass(JediTermWidget.class, c) != null);
    }

    /** The sidebar's own hide button shows only while the sidebar does; the rail has a show button instead. */
    private static boolean sidebarShown(JFrame frame) {
        return find(frame, AbstractButton.class, b -> b.getToolTipText() != null && b.getToolTipText().startsWith("Hide the sidebar")).isPresent();
    }

    private static String screen(JediTermWidget term) {
        return onEdtUnchecked(() -> term.getTerminalTextBuffer().getScreenLines());
    }

    private static <T extends Component> Optional<T> find(Container root, Class<T> type) {
        return find(root, type, c -> true);
    }

    private static <T extends Component> Optional<T> find(Container root, Class<T> type, Predicate<T> match) {
        AtomicReference<T> found = new AtomicReference<>();
        onEdtUnchecked(() -> {
            walk(root, type, match, found);
            return null;
        });
        return Optional.ofNullable(found.get());
    }

    private static <T extends Component> void walk(Component c, Class<T> type, Predicate<T> match, AtomicReference<T> found) {
        if (found.get() != null) {
            return;
        }
        if (type.isInstance(c) && c.isShowing() && match.test(type.cast(c))) {
            found.set(type.cast(c));
            return;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                walk(child, type, match, found);
            }
        }
    }

    /** Waits until the state loop's state holds still for 150 ms and the EDT has run what it was given. */
    private static void settle(Window w) {
        AtomicReference<AppState> last = new AtomicReference<>(w.loop().state());
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).pollDelay(Duration.ofMillis(150))
                .until(() -> {
                    SwingUtilities.invokeAndWait(() -> { });
                    return last.getAndSet(w.loop().state()) == w.loop().state();
                });
    }

    /** {@code value} on the EDT once it is neither null nor false, within {@code millis}. */
    private static <T> T await(String what, Supplier<T> value, long millis) {
        return Awaitility.await(what).atMost(Duration.ofMillis(millis)).pollInterval(Duration.ofMillis(50))
                .until(() -> onEdt(value::get), v -> v != null && !(v instanceof Boolean b && !b));
    }

    private static void await(String what, BooleanSupplier condition, long millis) {
        await(what, () -> condition.getAsBoolean() ? Boolean.TRUE : null, millis);
    }

    private static <T> T onEdt(Supplier<T> s) throws Exception {
        AtomicReference<T> r = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> r.set(s.get()));
        return r.get();
    }

    private static <T> T onEdtUnchecked(Supplier<T> s) {
        try {
            return SwingUtilities.isEventDispatchThread() ? s.get() : onEdt(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
