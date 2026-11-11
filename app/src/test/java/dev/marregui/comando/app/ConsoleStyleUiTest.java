package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.BracketBorder;
import dev.marregui.comando.ui.kit.theme.Gauge;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Style;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// The console style in the real window, off screen, on a data root and a preferences node of its own. Opens the
/// window, so this runs in `./gradlew :app:uiTest`; the window under each console theme is painted to
/// `build/snapshots/console-amber.png` and `console-cyan.png`.
/// - a plain theme after a console one leaves every region, the status bar and the session bar as they were
/// - a console theme frames the four regions with corner brackets in the tint's shade
/// - a console theme shows the plan's usage and the session's context as gauges
/// - under every console theme, dark or light, the regions and the bars paint from the theme chosen
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class ConsoleStyleUiTest {

    /** Each step's wait, as the other window tests have it; the window itself gets 20 s, its first layout. */
    private static final Duration WAIT = Duration.ofSeconds(10);
    /** The layer the theme fade puts the old window's picture on, as `ThemeFadeUiTest` reads it. */
    private static final int FADE_LAYER = JLayeredPane.DRAG_LAYER + 1;
    /** A mean of the three channels above this is a light colour, as `DiffThemeUiTest` reads it. */
    private static final int MIDDLE = 128;
    /** How far from its corner the top-left bracket is searched: past the length of its arms. */
    private static final int ARM = 10;
    /** The window's layout and checkpoint threshold kept here, apart from the user's and the other tests' nodes. */
    private static final String PREFS = "dev/marregui/comando-test-console";
    /** The four regions the frame goes round, by what the test calls them. */
    private static final List<String> REGIONS = List.of("sidebar", "editors", "lower panel", "side pane");
    private static final Insets NONE = new Insets(0, 0, 0, 0);

    private final String prefs = System.getProperty("comando.prefsNode");

    @TempDir
    Path home;

    @BeforeEach
    void ownPreferences() {
        System.setProperty("comando.prefsNode", PREFS);
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            return null;
        });
        if (prefs == null) {
            System.clearProperty("comando.prefsNode");
        } else {
            System.setProperty("comando.prefsNode", prefs);
        }
        Preferences.userRoot().node(PREFS).removeNode();
    }

    /// Under Graphite and gold the frame takes no room: every bracket frame in the window has no insets, and each
    /// region's frame holds its content within its other borders alone. Console amber, then Graphite and gold again,
    /// leaves the four regions where they were and the status bar's and the session bar's parts of the classes and at
    /// the bounds recorded, and no gauge shows.
    @Test
    void aPlainThemeAfterAConsoleOneLeavesTheWindowAsItWas() throws Exception {
        try (final App app = start("console-plain")) {
            feed(app.loop(), home);
            assertThat(onEdt(() -> NamedTheme.of(Theme.groundChosen(), Theme.tint()))).contains(NamedTheme.GRAPHITE_GOLD);
            assertThat(onEdt(Theme::style)).isEqualTo(Style.PLAIN);
            assertThat(onEdt(ConsoleStyleUiTest::bracketInsets)).as("the brackets' room under a plain theme")
                    .allSatisfy(i -> assertThat(i).isEqualTo(NONE));
            for (final String region : REGIONS) {
                final Optional<JComponent> frame = onEdt(() -> frameOf(region));
                if (frame.isPresent()) {
                    assertThat(onEdt(() -> SwingUtilities.calculateInnerArea(frame.get(), null)))
                            .as("what the " + region + " holds, within its other borders alone")
                            .isEqualTo(onEdt(() -> withinOthers(frame.get())));
                }
            }
            final Map<String, Rectangle> regions = onEdt(ConsoleStyleUiTest::regionBounds);
            final List<String> parts = onEdt(ConsoleStyleUiTest::barParts);
            assertThat(onEdt(ConsoleStyleUiTest::gauges)).as("no gauge under a plain theme").isEmpty();

            pick(NamedTheme.CONSOLE_AMBER);
            assertThat(onEdt(Theme::style)).isEqualTo(Style.CONSOLE);
            Awaitility.await("the gauges in place of the labels").atMost(WAIT)
                    .until(() -> !onEdt(ConsoleStyleUiTest::gauges).isEmpty());

            pick(NamedTheme.GRAPHITE_GOLD);
            assertThat(onEdt(Theme::style)).isEqualTo(Style.PLAIN);
            onEdt(() -> {
                frame().validate();
                return null;
            });
            assertThat(onEdt(ConsoleStyleUiTest::regionBounds)).as("the regions' bounds").isEqualTo(regions);
            assertThat(onEdt(ConsoleStyleUiTest::barParts)).as("the bars' parts, their classes and bounds")
                    .isEqualTo(parts);
            assertThat(onEdt(ConsoleStyleUiTest::gauges)).as("no gauge back under a plain theme").isEmpty();
        }
    }

    /// Under Console amber each of the four regions has a bracket frame 3 pixels wide on each side, and its top-left
    /// bracket is painted in the tint's shade.
    @Test
    void aConsoleThemeFramesTheFourRegions() throws Exception {
        try (final App app = start("console-frames")) {
            feed(app.loop(), home);
            pick(NamedTheme.CONSOLE_AMBER);
            final Color shade = onEdt(() -> Theme.tint().shade);
            for (final String region : REGIONS) {
                final JComponent frame = onEdt(() -> framed(region));
                final BracketBorder bracket = onEdt(() -> bracket(frame.getBorder())).orElseThrow();
                assertThat(onEdt(() -> bracket.getBorderInsets(frame))).as("the " + region + "'s frame")
                        .isEqualTo(new Insets(BracketBorder.INSET, BracketBorder.INSET, BracketBorder.INSET,
                                BracketBorder.INSET));
                final BufferedImage painted = onEdt(() -> paint(frame));
                final Point corner = onEdt(() -> offset(frame.getBorder(), frame)).orElseThrow();
                assertThat(onTopLeftBracket(painted, corner, shade))
                        .as("the " + region + "'s top-left bracket, in the tint's shade").isTrue();
            }
        }
    }

    /// Under Console amber a read of Claude's plan at 40 % and 96 % of its two windows shows two gauges in the status
    /// bar, the second in danger red; a session whose context fills half its window, past the checkpoint threshold of
    /// 40 %, shows the CTX gauge at 50 with its tick at 40, in the thinking purple.
    @Test
    void aConsoleThemeShowsThePlansUsageAndTheContextAsGauges() throws Exception {
        try (final App app = start("console-gauges")) {
            feed(app.loop(), home);
            pick(NamedTheme.CONSOLE_AMBER);
            Awaitility.await("Claude's two gauges").atMost(WAIT)
                    .until(() -> onEdt(ConsoleStyleUiTest::claudeGauges).size() == 2);
            final List<Gauge> usage = onEdt(ConsoleStyleUiTest::claudeGauges);
            assertThat(onEdt(() -> usage.stream().map(Gauge::percent).toList())).containsExactly(40, 96);
            assertThat(onEdt(() -> usage.getFirst().fill())).as("below the warning").isEqualTo(Theme.MUTED);
            assertThat(onEdt(() -> usage.getLast().fill())).as("past the danger threshold").isEqualTo(Theme.DANGER);

            Awaitility.await("the context's gauge").atMost(WAIT)
                    .until(() -> onEdt(ConsoleStyleUiTest::contextGauge).isPresent());
            final Gauge context = onEdt(ConsoleStyleUiTest::contextGauge).orElseThrow();
            assertThat(onEdt(context::percent)).isEqualTo(50);
            final OptionalInt tick = onEdt(context::tick);
            assertThat(tick).as("the tick at the checkpoint threshold").isEqualTo(OptionalInt.of(40));
            assertThat(onEdt(context::fill)).as("the checkpoint due").isEqualTo(Theme.THINKING);
        }
    }

    /// Under Console amber, Console cyan, Paper and ochre, and a light console theme from a file in the data root, the
    /// backgrounds and borders of the four regions, the status bar and the session bar, the brackets' pixels, the
    /// scroll bars' tracks and the gauges' fills are colours of the theme chosen, and the regions' grounds are light
    /// exactly when the theme is. The window under each built-in console theme is written to `build/snapshots/`.
    @Test
    void underEveryConsoleThemeTheWindowPaintsFromTheThemeChosen() throws Exception {
        final Path themes = Files.createDirectories(home.resolve("data").resolve("themes"));
        // Paper and ochre's colours, which pass every floor of the theme rules, under the console style
        Files.writeString(themes.resolve("console_paper.json"), """
                {"id": "console_paper", "label": "Console paper", "mode": "light", "style": "console",
                 "ground": {"label": "Paper", "color": "#F7F3EA"},
                 "tint": {"label": "Ochre", "highlight": "#3D2900", "body": "#5E4210", "shade": "#8A6524"}}
                """);
        try (final App app = start("console-colours")) {
            feed(app.loop(), home);
            final NamedTheme paper = onEdt(() -> NamedTheme.withId("console_paper")).orElseThrow();
            assertThat(paper.style).as("the file's style, in a light theme").isEqualTo(Style.CONSOLE);
            for (final NamedTheme t : List.of(NamedTheme.CONSOLE_AMBER, NamedTheme.CONSOLE_CYAN, NamedTheme.PAPER_OCHRE,
                    paper)) {
                pick(t);
                assertThat(onEdt(Theme::style)).as(t.label + "'s style").isEqualTo(t.style);
                onEdt(() -> {
                    frame().validate();
                    return null;
                });
                assertThat(onEdt(() -> notOfTheTheme(t))).as("what is painted in colours not of " + t.label).isEmpty();
                if (t == NamedTheme.CONSOLE_AMBER || t == NamedTheme.CONSOLE_CYAN) {
                    final Path out = Path.of("build/snapshots/" + t.id.replace('_', '-') + ".png");
                    Files.createDirectories(out.getParent());
                    ImageIO.write(onEdt(() -> paint(frame().getRootPane())), "png", out.toFile());
                }
            }
        }
    }

    /// A window on its own data root, laid out.
    private App start(final String version) throws Exception {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), version, () -> {
        });
        app.start();
        final StateLoop loop = app.loop();
        Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                -> loop.state().focused() != null && onEdt(() -> frame() != null));
        // Taller than the window's minimum, so its regions are laid out, as the other window tests wait
        Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(() -> frame().getHeight()) > 400);
        return app;
    }

    /// What the bars and the regions show: a session "work" in front, its context at half its window past the
    /// default checkpoint threshold of 40 %, a read of Claude's plan at 40 % and 96 %, and a view on the right side.
    /// The session is never started, so its turn's end runs no automatic checkpoint.
    private static void feed(final StateLoop loop, final Path dir) {
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", dir.toString())));
        Awaitility.await("its own panel").atMost(WAIT).until(() -> loop.state().homePanel("work").isPresent());
        loop.dispatch(new Intent.SessionTurnEnded("work", 0, -1, 50_000, 100_000, false, "", ""));
        Awaitility.await("the checkpoint due").atMost(WAIT)
                .until(() -> loop.state().session("work").orElseThrow().reflection().due());
        loop.dispatch(new Intent.PlanUsageRead(new PlanUsage(PlanUsage.CLAUDE, List.of(
                new PlanUsage.Window("Session", 40, ""), new PlanUsage.Window("Week", 96, "")),
                System.currentTimeMillis(), "")));
        loop.dispatch(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), Placement.SIDE));
        Awaitility.await("the four regions and the two bars showing").atMost(WAIT).until(() -> onEdt(() -> {
            for (final String region : REGIONS) {
                final JComponent c = region(region);
                if (c == null || !c.isShowing()) {
                    return false;
                }
            }
            final JComponent session = sessionBar();
            final JComponent status = statusBar();
            return session != null && status != null && status.isShowing();
        }));
    }

    /// Opens the theme chooser from the swatch, as a click does, turns its switch to the theme's mode, clicks the
    /// theme's card and closes the chooser with Esc; then waits for the fades to end.
    private static void pick(final NamedTheme theme) throws Exception {
        onEdt(() -> {
            final JButton swatch = find(frame(), JButton.class,
                    b -> "Choose the theme".equals(b.getAccessibleContext().getAccessibleName()));
            assertThat(swatch).as("the swatch beside New panel").isNotNull();
            swatch.doClick(0);
            return null;
        });
        onEdt(() -> {
            chooser().getRootPane().getActionMap().get(theme.ground.light ? "light" : "dark").actionPerformed(null);
            return null;
        });
        Awaitility.await("the switch at the theme's mode").atMost(WAIT)
                .until(() -> onEdt(Theme::light) == theme.ground.light);
        Awaitility.await("the fade of the switch ends").atMost(WAIT).until(() -> !onEdt(ConsoleStyleUiTest::fading));
        onEdt(() -> {
            final JDialog chooser = chooser();
            final JButton card = find(chooser, JButton.class,
                    b -> b.isShowing() && theme.label.equals(b.getAccessibleContext().getAccessibleName()));
            assertThat(card).as("the chooser's card for " + theme.label).isNotNull();
            card.doClick(0);
            chooser.getRootPane().getActionMap().get("close").actionPerformed(null);
            return null;
        });
        Awaitility.await(theme.label + " chosen").atMost(WAIT).until(()
                -> onEdt(() -> NamedTheme.of(Theme.groundChosen(), Theme.tint())).filter(t -> t == theme).isPresent());
        Awaitility.await("the fade ends").atMost(WAIT).until(() -> !onEdt(ConsoleStyleUiTest::fading));
    }

    /// The region `name`'s own component: the sidebar's tab strip, the main tabs, the lower panel's and the right
    /// side's tabs; null before it is built. EDT only.
    private static JComponent region(final String name) {
        return switch (name) {
            case "sidebar" -> find(frame(), JComponent.class,
                    c -> "Sidebar".equals(accessibleName(c)));
            case "editors" -> find(frame(), JComponent.class, c -> "workspace".equals(c.getName()));
            case "lower panel" -> find(frame(), JComponent.class, c -> "lower".equals(c.getName()));
            case "side pane" -> find(frame(), JComponent.class, c -> "side".equals(c.getName()));
            default -> throw new IllegalArgumentException("no such region [name=" + name + ']');
        };
    }

    /// The component that carries the region `name`'s frame, if one does: the region's own or one round it, below the
    /// split that divides it from the next region. EDT only.
    private static Optional<JComponent> frameOf(final String name) {
        for (Component c = region(name); c != null && !(c instanceof JSplitPane) && !(c instanceof JRootPane);
                c = c.getParent()) {
            if (c instanceof JComponent j && bracket(j.getBorder()).isPresent()) {
                return Optional.of(j);
            }
        }
        return Optional.empty();
    }

    /// The component that carries the region `name`'s frame. EDT only.
    private static JComponent framed(final String name) {
        return frameOf(name).orElseThrow(() -> new AssertionError("no bracket frame round the " + name));
    }

    /// The insets of every bracket frame on a component showing in the window. EDT only.
    private static List<Insets> bracketInsets() {
        final List<Insets> out = new ArrayList<>();
        for (final JComponent c : showing(frame(), JComponent.class)) {
            bracket(c.getBorder()).ifPresent(b -> out.add(b.getBorderInsets(c)));
        }
        return out;
    }

    /// The four regions' bounds in the window. EDT only.
    private static Map<String, Rectangle> regionBounds() {
        final Map<String, Rectangle> out = new LinkedHashMap<>();
        for (final String name : REGIONS) {
            final JComponent c = region(name);
            out.put(name, SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), frame().getRootPane()));
        }
        return out;
    }

    /// Every part showing in the status bar and the session bar, as its class and its bounds in the window. EDT only.
    private static List<String> barParts() {
        final List<String> out = new ArrayList<>();
        for (final JComponent bar : bars()) {
            for (final JComponent c : showing(bar, JComponent.class)) {
                out.add(c.getClass().getName() + " "
                        + SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), frame().getRootPane()));
            }
        }
        return out;
    }

    /// The status bar and the session bar. EDT only.
    private static List<JComponent> bars() {
        return List.of(statusBar(), sessionBar());
    }

    /// The status bar: the row whose middle is the notice named "Status"; null before it is built. EDT only.
    private static JComponent statusBar() {
        final JComponent notice = find(frame(), JComponent.class,
                c -> "Status".equals(accessibleName(c)));
        return notice == null ? null : (JComponent) notice.getParent();
    }

    /// `c`'s accessible name, or null for a component with no accessible context, as a bare `JComponent` has none.
    private static String accessibleName(final JComponent c) {
        return c.getAccessibleContext() == null ? null : c.getAccessibleContext().getAccessibleName();
    }

    /// The session bar showing, of the session in front; null while none shows. EDT only.
    private static JComponent sessionBar() {
        return find(frame(), JComponent.class, c -> c.isShowing() && "session bar".equals(c.getName()));
    }

    /// Every gauge showing in the window. EDT only.
    private static List<Gauge> gauges() {
        return showing(frame(), Gauge.class);
    }

    /// The status bar's gauges of Claude's plan, whose tooltip names Claude Code's `/usage`, in their order. EDT only.
    private static List<Gauge> claudeGauges() {
        return showing(statusBar(), Gauge.class).stream()
                .filter(g -> g.getToolTipText() != null && g.getToolTipText().contains("/usage")).toList();
    }

    /// The session bar's context gauge, captioned CTX. EDT only.
    private static Optional<Gauge> contextGauge() {
        return showing(sessionBar(), Gauge.class).stream().filter(g -> "CTX".equals(g.caption())).findFirst();
    }

    /// What in the regions and the bars is painted in a colour the theme `t` does not have, one line each: a region's
    /// ground on the other mode, a background, a border's colour, a bracket's pixel, a track or a fill. EDT only.
    private static List<String> notOfTheTheme(final NamedTheme t) throws Exception {
        final Set<Integer> theirs = colours(t);
        final List<String> out = new ArrayList<>();
        final List<JComponent> painted = new ArrayList<>();
        for (final String name : REGIONS) {
            final JComponent region = region(name);
            if (light(region.getBackground()) != t.ground.light) {
                out.add("the " + name + "'s ground " + hex(region.getBackground()) + " is not "
                        + (t.ground.light ? "light" : "dark"));
            }
            final Component top = frameOf(name).orElse(region);
            for (Component c = region; c instanceof JComponent j; c = c == top ? null : c.getParent()) {
                painted.add(j);
            }
        }
        for (final JComponent bar : bars()) {
            painted.add(bar);
            painted.addAll(showing(bar, JComponent.class));
        }
        for (final JComponent c : painted) {
            final String what = c.getClass().getSimpleName() + (c.getName() == null ? "" : " " + c.getName());
            if (c.isOpaque() && !theirs.contains(rgb(c.getBackground()))) {
                out.add(what + "'s background " + hex(c.getBackground()));
            }
            for (final Color line : borderColours(c.getBorder(), c)) {
                if (!theirs.contains(rgb(line))) {
                    out.add(what + "'s border " + hex(line));
                }
            }
            if (c instanceof JScrollBar s && !theirs.contains(rgb(s.getBackground()))) {
                out.add(what + "'s track " + hex(s.getBackground()));
            }
            if (c instanceof Gauge g && !theirs.contains(rgb(g.fill()))) {
                out.add("the gauge " + g.caption() + "'s fill " + hex(g.fill()));
            }
        }
        return out;
    }

    /// The colours of the theme `t`, chosen: its ground and tint, every colour `Theme` names, read in its mode, and
    /// every colour the look and feel was set up with on it. EDT only.
    private static Set<Integer> colours(final NamedTheme t) throws Exception {
        final Set<Integer> out = new HashSet<>();
        out.add(rgb(t.ground.color));
        out.add(rgb(t.tint.highlight));
        out.add(rgb(t.tint.body));
        out.add(rgb(t.tint.shade));
        out.add(rgb(Theme.ground()));
        for (final Field f : Theme.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Color.class.isAssignableFrom(f.getType())) {
                out.add(rgb((Color) f.get(null)));
            }
        }
        for (final Object v : UIManager.getLookAndFeelDefaults().values()) {
            if (v instanceof Color c) {
                out.add(rgb(c));
            }
        }
        return out;
    }

    /// The colours `border`'s lines take on `c`, compound borders opened; a bracket frame's are the opaque pixels it
    /// paints. EDT only.
    private static List<Color> borderColours(final Border border, final JComponent c) {
        final List<Color> out = new ArrayList<>();
        switch (border) {
            case CompoundBorder k -> {
                out.addAll(borderColours(k.getOutsideBorder(), c));
                out.addAll(borderColours(k.getInsideBorder(), c));
            }
            case LineBorder l -> out.add(l.getLineColor());
            case MatteBorder m when m.getMatteColor() != null -> out.add(m.getMatteColor());
            case BracketBorder b -> {
                final BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()),
                        BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = img.createGraphics();
                b.paintBorder(c, g, 0, 0, img.getWidth(), img.getHeight());
                g.dispose();
                final Set<Integer> seen = new HashSet<>();
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        final int argb = img.getRGB(x, y);
                        if (argb >>> 24 == 0xFF && seen.add(argb)) {
                            out.add(new Color(argb));
                        }
                    }
                }
            }
            case null, default -> {
                // no border, or one that draws no line of a colour of its own
            }
        }
        return out;
    }

    /// The bracket frame in `border`, compound borders opened, if it holds one.
    private static Optional<BracketBorder> bracket(final Border border) {
        return switch (border) {
            case BracketBorder b -> Optional.of(b);
            case CompoundBorder k -> bracket(k.getOutsideBorder()).or(() -> bracket(k.getInsideBorder()));
            case null, default -> Optional.empty();
        };
    }

    /// Where the bracket frame in `border` starts on `c`: past the insets of the borders outside it. EDT only.
    private static Optional<Point> offset(final Border border, final Component c) {
        return switch (border) {
            case BracketBorder _ -> Optional.of(new Point(0, 0));
            case CompoundBorder k -> {
                final Optional<Point> outside = offset(k.getOutsideBorder(), c);
                if (outside.isPresent()) {
                    yield outside;
                }
                final Insets o = k.getOutsideBorder() == null ? NONE : k.getOutsideBorder().getBorderInsets(c);
                yield offset(k.getInsideBorder(), c).map(p -> new Point(p.x + o.left, p.y + o.top));
            }
            case null, default -> Optional.empty();
        };
    }

    /// The rectangle `c` would hold its content in with its borders other than the bracket frame alone. EDT only.
    private static Rectangle withinOthers(final JComponent c) {
        final Insets in = others(c.getBorder(), c);
        return new Rectangle(in.left, in.top, c.getWidth() - in.left - in.right, c.getHeight() - in.top - in.bottom);
    }

    /// The insets of `border` on `c` without the bracket frame's, compound borders opened. EDT only.
    private static Insets others(final Border border, final Component c) {
        return switch (border) {
            case BracketBorder _ -> NONE;
            case CompoundBorder k -> {
                final Insets o = others(k.getOutsideBorder(), c);
                final Insets i = others(k.getInsideBorder(), c);
                yield new Insets(o.top + i.top, o.left + i.left, o.bottom + i.bottom, o.right + i.right);
            }
            case null -> NONE;
            default -> border.getBorderInsets(c);
        };
    }

    /// Whether a pixel of `img` on the top-left bracket's arms, which start at `corner`, is `shade`.
    private static boolean onTopLeftBracket(final BufferedImage img, final Point corner, final Color shade) {
        for (int y = corner.y; y < corner.y + ARM && y < img.getHeight(); y++) {
            for (int x = corner.x; x < corner.x + ARM && x < img.getWidth(); x++) {
                final boolean onArm = y < corner.y + BracketBorder.INSET || x < corner.x + BracketBorder.INSET;
                if (onArm && (img.getRGB(x, y) & 0xFFFFFF) == rgb(shade)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// `c` painted at its size. EDT only.
    private static BufferedImage paint(final JComponent c) {
        final BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        return img;
    }

    private static boolean light(final Color c) {
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3 > MIDDLE;
    }

    private static int rgb(final Color c) {
        return c.getRGB() & 0xFFFFFF;
    }

    private static String hex(final Color c) {
        return String.format("#%06X", rgb(c));
    }

    /// Whether the window shows the fade's picture of the theme before. EDT only.
    private static boolean fading() {
        return frame().getLayeredPane().getComponentCountInLayer(FADE_LAYER) > 0;
    }

    /// The theme chooser showing, or null. EDT only.
    private static JDialog chooser() {
        for (final Window w : frame().getOwnedWindows()) {
            if (w.isShowing() && w instanceof JDialog d && "Theme".equals(d.getTitle())) {
                return d;
            }
        }
        return null;
    }

    private static JFrame frame() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    /// Every component of `type` under `root` that is showing, in the order the tree holds them; none for a null root.
    private static <T extends Component> List<T> showing(final Component root, final Class<T> type) {
        final List<T> out = new ArrayList<>();
        if (root instanceof Container k) {
            for (final Component child : k.getComponents()) {
                if (child.isShowing()) {
                    if (type.isInstance(child)) {
                        out.add(type.cast(child));
                    }
                    out.addAll(showing(child, type));
                }
            }
        }
        return out;
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
        if (c == null) {
            return null;
        }
        if (type.isInstance(c) && which.test(type.cast(c))) {
            return type.cast(c);
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

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return out.get();
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
