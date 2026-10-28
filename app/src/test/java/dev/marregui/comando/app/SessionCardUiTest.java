package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
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
import java.awt.Point;
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

/// A click on a name in the sidebar shows a card in the real window. Opens the window, so this runs in
/// `./gradlew :app:uiTest`; the cards are painted to `build/snapshots/session-card.png` and `connection-card.png`.
/// - a session's name shows the card its panel's tab shows, which follows the pointer onto other names until it closes
/// - a connection's name shows a card of its facts alone, without a summary
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class SessionCardUiTest {

    @TempDir
    Path home;

    /// A click on a session's name shows its panel's card with its summary, its top at the row's and over the list's last
    /// pixels with no name under it; the name has no tooltip; the card follows onto another name and closes once left.
    @Test
    void aClickOnASessionsNameShowsTheTabsCardWhichFollowsThePointerOntoOtherNames() throws Exception {
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
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("other", home.toString())));
            JList<?> list = sessions();
            Awaitility.await("the rows").atMost(Duration.ofSeconds(10)).until(() -> onEdt(() -> list.getModel().getSize()) == 2);
            // by name: other above work
            Point workName = onEdt(() -> name(list, rowOf(list, "work")));
            Point otherName = onEdt(() -> name(list, rowOf(list, "other")));

            mouse(list, MouseEvent.MOUSE_MOVED, workName, 0);
            Awaitility.await("no card on resting").during(Duration.ofMillis(1200)).atMost(Duration.ofSeconds(3)).until(() -> card() == null);
            mouse(list, MouseEvent.MOUSE_CLICKED, workName, 1);
            Awaitility.await("the card").atMost(Duration.ofSeconds(5)).until(() -> card() != null);
            JComponent card = card();
            // beside the rows, its top at the row's, reaching back over the rows' last pixels: no name under it
            Rectangle placed = onEdt(() -> SwingUtilities.convertRectangle(card.getParent(), card.getBounds(), list));
            Rectangle workRow = onEdt(() -> list.getCellBounds(rowOf(list, "work"), rowOf(list, "work")));
            assertThat(placed.y).as("its top at the row's").isBetween(workRow.y - 8, workRow.y);
            assertThat(placed.x).as("over the list's last pixels, no gap").isLessThan(onEdt(() -> list.getVisibleRect().width));
            assertThat(placed.contains(workName) || placed.contains(otherName)).as("no name under it").isFalse();
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

            // onto another name, across the state icons: its card, without a click
            Point workIcon = onEdt(() -> new Point(workRow.x + 8, workRow.y + workRow.height / 2));
            Point otherIcon = onEdt(() -> {
                Rectangle r = list.getCellBounds(rowOf(list, "other"), rowOf(list, "other"));
                return new Point(r.x + 8, r.y + r.height / 2);
            });
            mouse(list, MouseEvent.MOUSE_MOVED, workIcon, 0);
            mouse(list, MouseEvent.MOUSE_MOVED, otherIcon, 0);
            mouse(list, MouseEvent.MOUSE_MOVED, otherName, 0);
            Awaitility.await("the card follows").atMost(Duration.ofSeconds(3)).until(() -> "other".equals(cardTitle()));

            // out of the card onto another name: that name's card
            mouse(list, MouseEvent.MOUSE_CLICKED, workName, 1);
            Awaitility.await("work's card").atMost(Duration.ofSeconds(3)).until(() -> "work".equals(cardTitle()));
            JComponent workCard = card();
            leaveCard(workCard, list, otherName);
            assertThat(card()).as("left, it closes").isNull();
            mouse(list, MouseEvent.MOUSE_MOVED, otherName, 0);
            Awaitility.await("and follows onto the name").atMost(Duration.ofSeconds(3)).until(() -> "other".equals(cardTitle()));

            // out of its left edge, toward the scrollbar's side of the list: onto a row, so it follows
            JComponent otherCard = card();
            Point leftEdge = onEdt(() -> {
                Point p = SwingUtilities.convertPoint(otherCard, new Point(-1, 5), list);
                p.x = Math.min(p.x, list.getVisibleRect().width - 1);
                return p;
            });
            leaveCard(otherCard, list, leftEdge);
            mouse(list, MouseEvent.MOUSE_MOVED, leftEdge, 0);
            Awaitility.await("still following").atMost(Duration.ofSeconds(3)).until(() -> cardTitle() != null);

            // out of the card onto what is not a name: it closes and follows no more
            JComponent shownCard = card();
            SwingUtilities.invokeAndWait(() -> {
                shownCard.dispatchEvent(new MouseEvent(shownCard, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false));
                shownCard.dispatchEvent(new MouseEvent(shownCard, MouseEvent.MOUSE_EXITED, 0, 0, shownCard.getWidth() + 40, 5, 0, false));
            });
            assertThat(card()).isNull();
            mouse(list, MouseEvent.MOUSE_MOVED, workName, 0);
            Awaitility.await("no card without a click").during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until(() -> card() == null);

            // its ✕ closes it, and it follows no more
            mouse(list, MouseEvent.MOUSE_CLICKED, workName, 1);
            Awaitility.await("work's card").atMost(Duration.ofSeconds(3)).until(() -> "work".equals(cardTitle()));
            SwingUtilities.invokeAndWait(() -> first(javax.swing.JButton.class, b -> "tab-card-close".equals(b.getName()) && b.isShowing()).doClick());
            assertThat(card()).isNull();
            mouse(list, MouseEvent.MOUSE_MOVED, otherName, 0);
            Awaitility.await("no card without a click").during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until(() -> card() == null);

            // a sub-agent's tab (record 109): in the lower panel, its own icon, its transcript and its state
            loop.dispatch(new Intent.ShowView("", "agent · review", new ViewSpec.Agent("work", "toolu_1", "review", "Updating while the sub-agent runs."),
                    Placement.LOWER));
            Awaitility.await("the tab").atMost(Duration.ofSeconds(5)).until(() -> onEdt(() -> first(JLabel.class,
                    l -> "agent-state".equals(l.getName()) && l.isShowing())) != null);
            assertThat(onEdt(() -> first(JLabel.class, l -> "agent-state".equals(l.getName()) && l.isShowing()).getText()))
                    .isEqualTo("Updating while the sub-agent runs.");
        } finally {
            app.close();
        }
    }

    /// A click on a connection's name shows a card of the connection's facts with no summary area.
    @Test
    void aClickOnAConnectionsNameShowsACardOfItsFactsWithoutASummary() throws Exception {
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

            mouse(list, MouseEvent.MOUSE_CLICKED, onEdt(() -> name(list, at)), 1);
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

    /** The index of the connection or session named {@code name} in {@code list}, -1 while it is not there. */
    private static int rowOf(JList<?> list, String name) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Object o = list.getModel().getElementAt(i);
            if (o instanceof ConnectionState c && c.name().equals(name) || o instanceof SessionState s && s.name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** A point on the name of row {@code i}, clear of its controls. */
    private static Point name(JList<?> list, int i) {
        Rectangle row = list.getCellBounds(i, i);
        return new Point(row.x + 60, row.y + 6);
    }

    /** A mouse event at {@code p} on {@code list}: a move, or a click of the first button. */
    private static void mouse(JList<?> list, int id, Point p, int clicks) throws Exception {
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(new MouseEvent(list, id, System.currentTimeMillis(), 0, p.x, p.y, clicks, false,
                clicks == 0 ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1)));
    }

    /** The pointer goes into {@code card} and leaves it for {@code p} on {@code list}. */
    private static void leaveCard(JComponent card, JList<?> list, Point p) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Point to = SwingUtilities.convertPoint(list, p, card);
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false));
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_EXITED, 0, 0, to.x, to.y, 0, false));
        });
    }

    /** The title of the card shown, or null. */
    private static String cardTitle() throws Exception {
        return card() == null ? null : onEdt(() -> first(JLabel.class, l -> "tab-card-title".equals(l.getName()) && l.isShowing()).getText());
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
