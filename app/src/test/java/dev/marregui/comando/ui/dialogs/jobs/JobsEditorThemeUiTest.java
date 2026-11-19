package dev.marregui.comando.ui.dialogs.jobs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.plaf.UIResource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// The kit jobs editor painted in a window off screen, under one dark and one light theme. Opens a window, so this
/// runs in `./gradlew :app:uiTest`.
/// - every opaque part paints a background the look and feel gave or the theme's: the selection's for a day the job
///   runs on, the list's for a row
/// - the editor painted is light exactly when the theme is
/// - a click on a label's ⓘ opens the explanation card over the window, a second click closes it
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class JobsEditorThemeUiTest {

    /// A mean of the three channels above this is a light colour, as `DiffThemeUiTest` reads it.
    private static final int MIDDLE = 128;

    @TempDir
    Path kit;

    private JFrame frame;

    /// The look and feel, set up once before any component exists, as the application does at its start.
    @BeforeAll
    static void lookAndFeel() throws Exception {
        onEdt(() -> {
            Theme.install();
            return null;
        });
    }

    /// The window and the theme as the next test expects them.
    @AfterEach
    void asBefore() throws Exception {
        onEdt(() -> {
            if (frame != null) {
                frame.dispose();
            }
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            return null;
        });
    }

    /// Under the theme given, the editor's opaque parts paint only the look and feel's or the theme's backgrounds, a
    /// day the job runs on is the selection's colour in bold, and the whole editor paints light exactly when the theme
    /// is light.
    @ParameterizedTest(name = "{0} and {1}")
    @CsvSource({"GRAPHITE, TREASURE, false", "PAPER, OCHRE, true"})
    void theEditorPaintsFromTheTheme(final Ground ground, final Tint tint, final boolean light) throws Exception {
        onEdt(() -> {
            Theme.choose(ground, tint);
            return null;
        });
        assertThat(onEdt(Theme::light)).as("the theme chosen is the one asked").isEqualTo(light);
        final JobsEditor e = onEdt(() -> {
            final JobsEditor editor = editor();
            frame = offScreen(editor.component());
            return editor;
        });

        final List<String> foreign = onEdt(() -> {
            final List<String> out = new ArrayList<>();
            final JList<JobsEditor.Row> list = e.list();
            final List<Integer> allowed = List.of(Theme.SELECTION.getRGB(), list.getBackground().getRGB(),
                    list.getSelectionBackground().getRGB());
            offTheme(e.component(), allowed, out);
            final Component row = list.getCellRenderer().getListCellRendererComponent(list,
                    list.getModel().getElementAt(1), 1, false, false);
            if (row.getBackground().getRGB() != list.getBackground().getRGB()) {
                out.add("an unselected row [colour=" + row.getBackground() + ']');
            }
            return out;
        });
        assertThat(foreign).as("parts painted outside the theme").isEmpty();

        assertThat(onEdt(() -> e.day(0).getBackground().getRGB())).as("Monday, a day it runs on")
                .isEqualTo(onEdt(Theme.SELECTION::getRGB));
        assertThat(onEdt(() -> e.day(0).getFont().isBold())).isTrue();
        assertThat(onEdt(() -> e.day(1).getFont().getStyle())).as("Tuesday, a day it does not run on")
                .isEqualTo(Font.PLAIN);
        assertThat(onEdt(() -> paintedLight(e.component()))).as("the editor painted light exactly when the theme is")
                .isEqualTo(light);
    }

    /// A click on the Program label's ⓘ puts the explanation card over the window; a second click takes it away.
    @ParameterizedTest(name = "{0} and {1}")
    @CsvSource({"GRAPHITE, TREASURE", "PAPER, OCHRE"})
    void theInfoMarkOpensAndClosesTheExplanation(final Ground ground, final Tint tint) throws Exception {
        onEdt(() -> {
            Theme.choose(ground, tint);
            return null;
        });
        final JobsEditor e = onEdt(() -> {
            final JobsEditor editor = editor();
            frame = offScreen(editor.component());
            return editor;
        });
        final JLabel about = onEdt(() -> (JLabel) named(e.component(), "about:" + KitJob.COMMAND_FIELD));
        assertThat(about).as("the Program label carries an ⓘ").isNotNull();
        assertThat(onEdt(this::cardsShown)).isZero();
        onEdt(() -> click(about));
        assertThat(onEdt(this::cardsShown)).as("the card is over the window").isEqualTo(1);
        onEdt(() -> click(about));
        assertThat(onEdt(this::cardsShown)).as("the second click closes it").isZero();
    }

    /// A window left of every screen holding `content`, as the application's is under `comando.offScreen`.
    private static JFrame offScreen(final JComponent content) {
        Rectangle screens = new Rectangle();
        for (final GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens = screens.union(d.getDefaultConfiguration().getBounds());
        }
        final JFrame f = new JFrame("kit jobs");
        f.setUndecorated(true);
        f.setAutoRequestFocus(false);
        f.setFocusableWindowState(false);
        f.setContentPane(content);
        f.setBounds(screens.x - 1_800, screens.y, 1_000, 640);
        f.setVisible(true);
        f.validate();
        return f;
    }

    /// Each opaque component under `c` whose background is set in code and is none of `allowed`.
    private static void offTheme(final Component c, final List<Integer> allowed, final List<String> out) {
        if (c instanceof JComponent j && j.isOpaque() && j.isVisible()) {
            final Color b = j.getBackground();
            if (b != null && !(b instanceof UIResource) && !allowed.contains(b.getRGB())) {
                out.add(j.getClass().getSimpleName() + (j.getName() == null ? "" : " " + j.getName())
                        + " [colour=" + b + ']');
            }
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                offTheme(child, allowed, out);
            }
        }
    }

    /// Whether `c`, printed, is light: the mean of its pixels' three channels above [#MIDDLE].
    private static boolean paintedLight(final JComponent c) {
        final BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            c.printAll(g);
        } finally {
            g.dispose();
        }
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final Color p = new Color(image.getRGB(x, y));
                sum += p.getRed() + p.getGreen() + p.getBlue();
            }
        }
        return sum / (3L * image.getWidth() * image.getHeight()) > MIDDLE;
    }

    /// A left click on `c`, as a press, release and click in its middle.
    private static Void click(final Component c) {
        for (final int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            c.dispatchEvent(new MouseEvent(c, id, System.currentTimeMillis(), 0, c.getWidth() / 2, c.getHeight() / 2, 1,
                    false, MouseEvent.BUTTON1));
        }
        return null;
    }

    /// The component under `c` named `name`, or null.
    private static Component named(final Component c, final String name) {
        if (name.equals(c.getName())) {
            return c;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final Component found = named(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }

    private JobsEditor editor() {
        final KitJob drift = new KitJob("kb-drift", "KB drift report", List.of("MON"), "07:30",
                List.of("tools/kb-drift/kb-drift"), Map.of(), "", true);
        final KitJob curator = new KitJob("curator", "", List.of("MON"), "07:45", List.of("tools/curator/curator"),
                Map.of(), "", true);
        final List<KitJobs.Entry> entries = List.of(
                new KitJobs.Entry(drift, true, true, true, false, "2026-11-16T07:30+01:00", 0, "2026-11-23T07:30+01:00",
                        kit.resolve(".kit-jobs/kb-drift.log").toString()),
                new KitJobs.Entry(curator, true, true, true, false, "2026-11-16T07:45+01:00", 1,
                        "2026-11-23T07:45+01:00", kit.resolve(".kit-jobs/curator.log").toString()));
        return new JobsEditor(entries, kit, new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path tools) {
                return Optional.empty();
            }

            @Override
            public void run(final String name) {
                // nothing runs
            }

            @Override
            public void open(final Path file) {
                // nothing is opened
            }
        });
    }

    /// How many visible components sit over the window's content in its layered pane: the explanation card when open.
    private int cardsShown() {
        int n = 0;
        final JLayeredPane layers = frame.getLayeredPane();
        for (final Component c : layers.getComponents()) {
            if (c.isVisible() && layers.getLayer(c) != JLayeredPane.FRAME_CONTENT_LAYER) {
                n++;
            }
        }
        return n;
    }
}
