package dev.marregui.comando.app;

import com.jediterm.terminal.ui.JediTermWidget;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Topic;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingPorts;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Gold;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.shell.ComandoUi;
import dev.marregui.comando.ui.shell.ServerToggle;
import dev.marregui.comando.ui.shell.Setup;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;
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
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private record Window(StateLoop loop, ComandoUi ui, List<Intent> intents, JFrame frame, TranscriptRegistry transcripts) implements AutoCloseable {
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
        Theme.gold(Gold.TREASURE);
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private Window open(String keys) throws Exception {
        return open(keys, null);
    }

    /** A window whose embedded server is {@code server}, or none when null. */
    private Window open(String keys, ServerToggle server) throws Exception {
        return open(keys, server, TopicPort.NONE);
    }

    /** A window whose tabs' topics {@code topics} reads. */
    private Window open(String keys, ServerToggle server, TopicPort topics) throws Exception {
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
        TranscriptRegistry transcripts = new TranscriptRegistry();
        ComandoUi ui = new ComandoUi(new ComandoUi.Dependencies(recording, loop::state, loop, transcripts, new ResultRegistry(),
                new ConsoleRegistry(), new SchemaRegistry(), new Texts(), List::of, server, Setup.NONE, "keys", null, null, () -> { }));
        effects.target = new EffectRouter(ports, ports, ports, ui, ports, ports);
        open.add(() -> {
            ui.close();
            loop.close();
        });
        List<Frame> before = List.of(Frame.getFrames());
        loop.start();
        ui.topics(topics);
        ui.start();
        // the frame this start opened; an earlier test's may not be gone yet
        JFrame frame = onEdt(() -> (JFrame) Stream.of(Frame.getFrames())
                .filter(f -> f instanceof JFrame && f.isShowing() && !before.contains(f)).findFirst().orElseThrow());
        return new Window(loop, ui, sent, frame, transcripts);
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

    /** The first panel on a fresh data root names menu items; each must be one the menu bar has. */
    @Test
    void theWelcomeTextNamesOnlyMenuItemsTheBarHas() throws Exception {
        Window w = open("mac", new ServerToggle() {
            @Override
            public String name() {
                return "QuestDB";
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public void toggle() {
            }
        });
        // a command in no menu is grouped by its area ("Panels"), which is no menu of the bar
        List<String> menus = onEdt(() -> {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < w.frame().getJMenuBar().getMenuCount(); i++) {
                names.add(w.frame().getJMenuBar().getMenu(i).getText());
            }
            return names;
        });
        List<String> items = w.ui().commands().stream().filter(c -> c.group() != null && menus.contains(c.group().split(" ▸ ")[0]))
                .map(c -> c.group() + " ▸ " + c.label()).toList();
        List<String> named = new ArrayList<>();
        Matcher m = Pattern.compile("\\b\\p{Lu}\\p{L}+ ▸ ").matcher(Welcome.TEXT);
        while (m.find()) {
            named.add(Welcome.TEXT.substring(m.start()));
        }
        assertThat(named).as("the welcome names menu items").isNotEmpty();
        for (String path : named) {
            assertThat(items).as("a menu item at the start of: " + path.lines().findFirst().orElse(path)).anyMatch(path::startsWith);
        }
    }

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
    void restingOnATabShowsItsCardWithTheTopicWhichStaysUntilTheCardIsClicked() throws Exception {
        List<String> sources = new CopyOnWriteArrayList<>();
        Window w = open("mac", null, (key, source, urgent, done) -> {
            sources.add(source);
            done.accept(new Topic("Replaying the snapshot session", 1L, ""));
        });
        settle(w);
        JTabbedPane tabs = await("the panel tabs", () -> find(w.frame(), JTabbedPane.class, t -> "workspace".equals(t.getName())).orElse(null),
                WAIT_MILLIS);
        // the first tab's panel holds a text to read its topic from
        onEdt(() -> {
            find((Container) tabs.getComponentAt(0), RSyntaxTextArea.class).orElseThrow().setText("-- the snapshot's queries\nSELECT 1;");
            return null;
        });
        Rectangle tab = onEdt(() -> tabs.getUI().getTabBounds(tabs, 0));
        onEdt(() -> {
            tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, tab.x + 8, tab.y + tab.height / 2, 0, false));
            return null;
        });
        JComponent card = await("the tab's card", () -> find(w.frame(), JComponent.class, c -> "tab-card".equals(c.getName()) && c.isShowing())
                .orElse(null), WAIT_MILLIS);
        JTextArea topic = onEdt(() -> find(card, JTextArea.class, l -> "tab-card-topic".equals(l.getName())).orElseThrow());
        await("the topic", () -> "Replaying the snapshot session".equals(topic.getText()), WAIT_MILLIS);
        assertThat(sources).singleElement().asString().contains("SELECT 1;");
        assertThat(onEdt(() -> tabs.getToolTipTextAt(0))).as("the card, not a tooltip").isNull();

        onEdt(() -> {
            tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -5, -5, 0, false));
            return null;
        });
        // the card stays when the pointer leaves, past the hover delay
        Awaitility.await("the card staying").during(Duration.ofMillis(ToolTipManager.sharedInstance().getInitialDelay() + 200L))
                .atMost(Duration.ofSeconds(5)).until(() -> onEdt(card::isShowing));
        onEdt(() -> {
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false,
                    MouseEvent.BUTTON1));
            return null;
        });
        assertThat(onEdt(card::isShowing)).as("a click on the card closes it").isFalse();
    }

    @Test
    void theGoldIsChosenBesideTheSessionsClearAndKeptForTheNextWindow() throws Exception {
        Window w = open("mac");
        settle(w);
        AbstractButton gold = await("the gold button", () -> find(w.frame(), AbstractButton.class,
                b -> "Choose the gold".equals(b.getAccessibleContext().getAccessibleName())).orElse(null), WAIT_MILLIS);
        List<String> row = onEdt(() -> Stream.of(gold.getParent().getComponents())
                .map(c -> ((JComponent) c).getAccessibleContext().getAccessibleName()).toList());
        assertThat(row).as("left of Clear").containsExactly("Choose the gold", "Clear the Session tab");
        assertThat(Theme.gold()).as("by default").isEqualTo(Gold.TREASURE);
        // a session bound to the panel, with a turn whose answer is its report, in the Session tab
        w.loop().dispatch(new Intent.SaveSession("", SessionSpec.fresh("gilt", "/tmp")));
        w.loop().dispatch(new Intent.BindSession(w.loop().state().focusedPanel().id(), "gilt"));
        onEdt(() -> {
            Transcript t = w.transcripts().of("gilt");
            t.append(new Block.UserPrompt("go", "p", ""));
            t.append(new Block.AssistantText("m", 0, "# Result\n\nDone.", false, ""));
            t.append(new Block.TurnEnd(0.1, 1_000, 2, false, ""));
            t.changed();
            return null;
        });
        w.loop().dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
        settle(w);
        TranscriptCanvas canvas = await("the transcript", () -> find(w.frame(), TranscriptCanvas.class).orElse(null), WAIT_MILLIS);
        await("the turn on the canvas", () -> canvas.contentHeight() > 100, WAIT_MILLIS);
        assertThat(goldPixels(canvas, Gold.TREASURE)).as("the report's bar").isPositive();

        onEdt(() -> {
            gold.doClick();
            return null;
        });
        // the menu shows in the frame or, heavyweight, in a window the frame owns
        JRadioButtonMenuItem champagne = await("the menu", () -> Stream.concat(Stream.of(w.frame()), Stream.of(w.frame().getOwnedWindows()))
                .map(win -> find(win, JRadioButtonMenuItem.class, i -> Gold.CHAMPAGNE.label.equals(i.getText())).orElse(null))
                .filter(Objects::nonNull).findFirst().orElse(null), WAIT_MILLIS);
        List<String> ticked = onEdt(() -> Stream.of(champagne.getParent().getComponents())
                .map(c -> (JRadioButtonMenuItem) c).filter(AbstractButton::isSelected).map(AbstractButton::getText).toList());
        assertThat(ticked).as("the gold chosen is ticked").containsExactly(Gold.TREASURE.label);
        onEdt(() -> {
            champagne.doClick();
            return null;
        });
        assertThat(Theme.gold()).isEqualTo(Gold.CHAMPAGNE);
        assertThat(Preferences.userRoot().node(PREFS).get("theme.gold", null)).isEqualTo("CHAMPAGNE");
        assertThat(goldPixels(canvas, Gold.CHAMPAGNE)).as("the answer laid out again in the gold chosen, at once").isPositive();
        assertThat(goldPixels(canvas, Gold.TREASURE)).isZero();

        Theme.gold(Gold.TREASURE);
        open("mac");
        assertThat(Theme.gold()).as("a new window paints in the gold kept").isEqualTo(Gold.CHAMPAGNE);
    }

    /** How many pixels of the canvas, painted as it stands, are {@code gold}'s highlight: the top of the report's bar. */
    private static int goldPixels(TranscriptCanvas canvas, Gold gold) throws Exception {
        return onEdt(() -> {
            int width = 800;
            canvas.ensureLayout(width);
            canvas.setSize(width, canvas.contentHeight());
            BufferedImage img = new BufferedImage(width, canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            canvas.paint(g);
            g.dispose();
            int n = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) == (gold.highlight.getRGB() & 0xFFFFFF)) {
                        n++;
                    }
                }
            }
            return n;
        });
    }

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
