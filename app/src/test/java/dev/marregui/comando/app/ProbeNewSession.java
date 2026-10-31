package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.core.model.KitRoot;

import static org.awaitility.Awaitility.await;
/** Throwaway probe: Add session, click the name field, type, report text, caret and focus. */
public final class ProbeNewSession {
    public static void main(final String[] args) throws Exception {
        final Path home = Files.createTempDirectory("comando-probe");
        final App app = new App(new AppConfig(home, ClaudeSettings.findExecutable(), KitRoot.current(), true), "probe", () -> {
        });
        app.start();
        Thread.sleep(2500);
        final var loop = app.loop();
        loop.dispatch(new dev.marregui.comando.core.intent.Intent.SaveSession("", dev.marregui.comando.core.model.SessionSpec.fresh("standard", System.getProperty("user.home")).withModel("haiku")));
        loop.dispatch(new dev.marregui.comando.core.intent.Intent.BindSession(loop.state().focused(), "standard"));
        final Thread pump = new Thread(() -> {
            try {
                for (int i = 0; ; i++) {
                    loop.dispatch(new dev.marregui.comando.core.intent.Intent.Notify(dev.marregui.comando.core.state.Notice.Level.INFO, "tick " + i, false));
                    Thread.sleep(40);
                }
            } catch (final InterruptedException e) {
                // done
            }
        });
        pump.setDaemon(true);
        pump.start();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner",
                e -> System.out.println("FOCUS -> " + describe((Component) e.getNewValue())));
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusedWindow",
                e -> System.out.println("WINDOW -> " + (e.getNewValue() == null ? null : ((Window) e.getNewValue()).getClass().getSimpleName())));
        final Robot r = new Robot();
        r.setAutoDelay(40);
        scenario(r, "add, click, type", "New session…", false, true);
        scenario(r, "add again, click, type", "New session…", false, true);
        scenario(r, "add, type without click", "New session…", false, false);
        SwingUtilities.invokeAndWait(() -> { for (final Window w : Window.getWindows()) { final AbstractButton b = find(w, "Save"); if (b != null && w instanceof JDialog) { b.doClick(); } } });
        Thread.sleep(800);
        System.out.println("names after save: " + loop.state().sessions().stream().map(x -> x.name()).toList());
        scenario(r, "after save, click, type", null, true, true);
        app.close();
        System.exit(0);
    }

    static void clickTip(final String tip) throws Exception {
    }

    static void scenario(final Robot r, final String label, final String addTip, final boolean selectAllFirst, final boolean click) throws Exception {
        System.out.println("=== " + label);
        if (addTip != null) {
            // the Sessions tab's menu holds New session…
            SwingUtilities.invokeAndWait(() -> {
                for (final Window w : Window.getWindows()) {
                    final AbstractButton tab = findNamed(w, "Sessions");
                    if (tab != null && !(w instanceof JDialog)) {
                        tab.dispatchEvent(new MouseEvent(tab, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                                0, 4, 4, 1, true, MouseEvent.BUTTON3));
                        return;
                    }
                }
            });
            final AtomicReference<AbstractButton> item = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                SwingUtilities.invokeAndWait(() -> {
                    for (final Window w : Window.getWindows()) {
                        final AbstractButton b = findText(w, addTip);
                        if (b != null && b.isShowing()) {
                            item.set(b);
                        }
                    }
                });
                return item.get() != null;
            });
            SwingUtilities.invokeAndWait(() -> item.get().doClick());
            Thread.sleep(1200);
        }
        final AtomicReference<JTextField> name = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            for (final Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible()) {
                    name.set(field(d));
                    System.out.println("dialog [" + d.getTitle() + "] focused=" + d.isFocused());
                }
            }
            final JTextField f = name.get();
            System.out.println("before text=[" + f.getText() + "] caret=" + f.getCaretPosition() + " sel=" + f.getSelectedText());
            if (f.getCaretListeners().length < 3) {
                f.addCaretListener(e -> {
                    if (e.getDot() == 0) {
                        new Throwable("caret to 0").printStackTrace(System.out);
                    }
                });
            }
        });
        if (click) {
            final Point p = name.get().getLocationOnScreen();
            r.mouseMove(p.x + name.get().getWidth() - 10, p.y + name.get().getHeight() / 2);
            r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(400);
        }
        for (final int k : new int[]{KeyEvent.VK_X, KeyEvent.VK_Y, KeyEvent.VK_Z}) {
            r.keyPress(k);
            r.keyRelease(k);
        }
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> System.out.println("after text=[" + name.get().getText() + "] caret=" + name.get().getCaretPosition()
                + " focus=" + describe(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())));
    }

    static String describe(final Component c) {
        return c == null ? "null" : c.getClass().getSimpleName() + "(" + c.getName() + ")";
    }

    static AbstractButton findNamed(final Component c, final String name) {
        if (c instanceof AbstractButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
            return b;
        }
        if (c instanceof Container k) {
            for (final Component x : k.getComponents()) {
                final AbstractButton b = findNamed(x, name);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static AbstractButton findText(final Component c, final String text) {
        if (c instanceof AbstractButton b && text.equals(b.getText())) {
            return b;
        }
        if (c instanceof Container k) {
            for (final Component x : k.getComponents()) {
                final AbstractButton b = findText(x, text);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static AbstractButton find(final Component c, final String tip) {
        if (c instanceof AbstractButton b && tip.equals(b.getToolTipText())) {
            return b;
        }
        if (c instanceof Container k) {
            for (final Component x : k.getComponents()) {
                final AbstractButton b = find(x, tip);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static JTextField field(final Component c) {
        if (c instanceof JTextField t && c.isShowing()) {
            return t;
        }
        if (c instanceof Container k) {
            for (final Component x : k.getComponents()) {
                final JTextField t = field(x);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }
}
