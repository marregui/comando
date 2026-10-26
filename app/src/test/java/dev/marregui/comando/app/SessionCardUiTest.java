package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.ConnectionState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pointer resting on a session's row in the sidebar shows the card a tab shows, with the session's facts, in the
 * real window. Opens the window, so this runs in {@code ./gradlew :app:uiTest}; the card is painted to
 * {@code build/snapshots/session-card.png} to be looked at. A connection's row shows a card of its facts alone, painted to
 * {@code connection-card.png}.
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class SessionCardUiTest {

    @TempDir
    Path home;

    @Test
    void restingOnASessionRowShowsTheTabsCardWithTheSessionsDetails() throws Exception {
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "card", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            // the tab in front is kept across starts: whatever an earlier window left, Sessions comes to the front
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && front("Sessions"));
            Awaitility.await("the sessions").atMost(Duration.ofSeconds(5)).until(() -> sessions() != null);
            // a new session comes with its own panel (record 88)
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            JList<?> list = sessions();
            Awaitility.await("the row").atMost(Duration.ofSeconds(10)).until(() -> onEdt(() -> list.getModel().getSize()) == 1);

            SwingUtilities.invokeAndWait(() -> {
                Rectangle row = list.getCellBounds(0, 0);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, row.x + 60, row.y + 6, 0, false));
            });
            Awaitility.await("the card").atMost(Duration.ofSeconds(5)).until(() -> card() != null);
            JComponent card = card();
            // its title, the session's name, and its facts, each once (record 90)
            assertThat(onEdt(() -> first(JLabel.class, l -> "tab-card-title".equals(l.getName())).getText())).isEqualTo("work");
            List<String> facts = onEdt(() -> {
                List<JLabel> out = new ArrayList<>();
                collect(card, JLabel.class, l -> "tab-card-fact".equals(l.getName()), out);
                return out.stream().map(JLabel::getText).toList();
            });
            assertThat(facts).first().isEqualTo("stopped");
            assertThat(facts).anyMatch(f -> f.startsWith("default · effort")).noneMatch(f -> f.equals("work"));
            assertThat(onEdt(() -> text(card, "tab-card-topic"))).as("the panel's card, whose summary is read").isNotNull();
            assertThat(onEdt(() -> list.getToolTipText(new MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0,
                    list.getCellBounds(0, 0).x + 60, list.getCellBounds(0, 0).y + 6, 0, false)))).as("no tooltip over the text").isNull();

            Path out = Path.of("build/snapshots/session-card.png");
            Files.createDirectories(out.getParent());
            BufferedImage image = onEdt(() -> {
                BufferedImage b = new BufferedImage(Math.max(1, card.getWidth()), Math.max(1, card.getHeight()), BufferedImage.TYPE_INT_ARGB);
                card.paint(b.getGraphics());
                return b;
            });
            ImageIO.write(image, "png", out.toFile());
            BufferedImage rows = onEdt(() -> {
                BufferedImage b = new BufferedImage(Math.max(1, list.getWidth()), Math.max(1, list.getCellBounds(0, 0).height + 8),
                        BufferedImage.TYPE_INT_ARGB);
                list.paint(b.getGraphics());
                return b;
            });
            ImageIO.write(rows, "png", out.resolveSibling("session-rows.png").toFile());
        } finally {
            app.close();
        }
    }

    @Test
    void restingOnAConnectionRowShowsACardOfItsFactsWithoutASummary() throws Exception {
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "card", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            // the tab in front is kept across starts: whatever an earlier window left, Sessions comes to the front
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && front("Sessions"));
            Awaitility.await("the sessions").atMost(Duration.ofSeconds(5)).until(() -> sessions() != null);
            // a port nothing listens on: the row stays disconnected
            loop.dispatch(new Intent.SaveConnection("", new ConnectionSpec("live", "localhost", 1, "qdb", "admin", "secret", true)));
            assertThat(front("Connections")).isTrue();
            JList<?> list = onEdt(() -> first(JList.class, l -> "Connections".equals(l.getAccessibleContext().getAccessibleName()) && l.isShowing()));
            // the first start also writes a local QuestDB connection: find the row of the one saved here
            Awaitility.await("the row").atMost(Duration.ofSeconds(10)).until(() -> onEdt(() -> rowOf(list, "live")) >= 0);
            int at = onEdt(() -> rowOf(list, "live"));

            SwingUtilities.invokeAndWait(() -> {
                Rectangle row = list.getCellBounds(at, at);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, row.x + 60, row.y + 6, 0, false));
            });
            Awaitility.await("the card").atMost(Duration.ofSeconds(5)).until(() -> card() != null);
            JComponent card = card();
            assertThat(onEdt(() -> first(JLabel.class, l -> "tab-card-title".equals(l.getName())).getText())).isEqualTo("live");
            List<String> facts = onEdt(() -> {
                List<JLabel> out = new ArrayList<>();
                collect(card, JLabel.class, l -> "tab-card-fact".equals(l.getName()), out);
                return out.stream().map(JLabel::getText).toList();
            });
            assertThat(facts).first().asString().startsWith("disconnected");
            assertThat(facts).contains("qdb on localhost:1", "admin", "yes; Claude's writes are refused").noneMatch(f -> f.contains("secret"));
            assertThat(onEdt(() -> text(card, "tab-card-topic"))).as("no summary").isNull();
            assertThat(onEdt(() -> first(JComponent.class, c -> "tab-card-heading".equals(c.getName()) && c.isShowing()))).isNull();

            Path out = Path.of("build/snapshots/connection-card.png");
            Files.createDirectories(out.getParent());
            ImageIO.write(onEdt(() -> paint(card, card.getHeight())), "png", out.toFile());
            ImageIO.write(onEdt(() -> paint(list, list.getCellBounds(list.getModel().getSize() - 1, list.getModel().getSize() - 1).y
                    + list.getCellBounds(0, 0).height + 8)), "png", out.resolveSibling("connection-rows.png").toFile());
        } finally {
            app.close();
        }
    }

    /** Clicks the sidebar's tab header named {@code tab}, as a user would; false while the window has none. */
    private static boolean front(String tab) throws Exception {
        return onEdt(() -> {
            JToggleButton header = first(JToggleButton.class, b -> tab.equals(b.getAccessibleContext().getAccessibleName()) && b.isShowing());
            if (header != null) {
                header.doClick();
            }
            return header != null;
        });
    }

    /** The index of the connection named {@code name} in {@code list}, -1 while it is not there. */
    private static int rowOf(JList<?> list, String name) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (list.getModel().getElementAt(i) instanceof ConnectionState c && c.name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static BufferedImage paint(JComponent c, int height) {
        BufferedImage b = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        c.paint(b.getGraphics());
        return b;
    }

    /** The sidebar's sessions list, once the window shows it. */
    private static JList<?> sessions() throws Exception {
        return onEdt(() -> first(JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()) && l.isShowing()));
    }

    /** The card, while it shows. */
    private static JComponent card() throws Exception {
        return onEdt(() -> first(JComponent.class, c -> "tab-card".equals(c.getName()) && c.isShowing()));
    }

    private static String text(Container root, String name) {
        List<JTextArea> out = new ArrayList<>();
        collect(root, JTextArea.class, a -> name.equals(a.getName()) && a.isVisible(), out);
        return out.isEmpty() ? null : out.getFirst().getText();
    }

    private static <T extends Component> T first(Class<T> type, Predicate<T> which) {
        List<T> out = new ArrayList<>();
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(Component c, Class<T> type, Predicate<T> which, List<T> out) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }

    private static <T> T onEdt(EdtCall<T> call) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }
}
