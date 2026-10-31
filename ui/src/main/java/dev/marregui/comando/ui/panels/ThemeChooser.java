package dev.marregui.comando.ui.panels;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.ThemePreview;

/**
 * The theme chooser (record 128): a popover under the swatch that opened it, right-aligned with it, with a Dark | Light
 * toggle over a grid of cards of that mode's themes, three to a row, scrolling past four rows. Each light theme lies in
 * the slot of the dark one it is the twin of, so flipping the toggle turns each card into its twin in place; the
 * themes no twin pairs, the user's files among them, follow. The toggle is a switch of polished metal, a moon on its
 * knob in dark and a sun in light ({@link ModeSwitch}). Each card draws its theme small in its own colours
 * ({@link ThemePreview}) over its name; the theme chosen has a ring in its tint. The toggle starts at the mode of the
 * theme chosen; flipping it turns the whole window to the other mode, the twin of the theme chosen, and the chooser
 * stays open on that mode's cards. A click, or ⏎ on a card, chooses its theme for the whole window and leaves the
 * chooser open to try the next; the theme last chosen is the one kept. The arrows move between cards, D and L flip the
 * toggle; the ✕ at the top right, Esc, a click elsewhere or the swatch again closes. EDT only.
 */
public final class ThemeChooser {

    public static final String TITLE = "Theme";
    /** A card's preview, in points. */
    static final int PREVIEW_W = 188;
    static final int PREVIEW_H = 96;
    /** Cards to a row, and the rows shown before the grid scrolls. */
    static final int COLUMNS = 3;
    static final int ROWS_SHOWN = 4;

    private final JDialog dialog;
    private final JPanel grid = new JPanel(new GridLayout(0, COLUMNS, Theme.SPACE_M, Theme.SPACE_M));
    private final ModeSwitch mode;
    private final Consumer<NamedTheme> chosen;
    private final List<Card> cards = new ArrayList<>();
    /** When a chooser last closed by losing the focus. */
    private static long closedNanos = Long.MIN_VALUE / 2;
    /** How soon after closing that way a click on the swatch is the click that closed it. */
    private static final long CLICK_NANOS = 300_000_000L;
    /** The mode whose cards the grid shows. */
    private boolean shownLight;
    /** Closed by Esc, its close button or {@link #close}: the deactivation that follows is not a click elsewhere. */
    private boolean closing;

    private ThemeChooser(final Window owner, final Consumer<NamedTheme> chosen) {
        this.chosen = chosen;
        dialog = new JDialog(owner, TITLE);
        dialog.setUndecorated(true);
        dialog.setModal(false);
        final boolean lightNow = Theme.light();
        mode = new ModeSwitch(lightNow);
        mode.onChange(this::flip);
        final JPanel toggle = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        toggle.add(mode);
        // the close button at the top right, as a card's; the theme last chosen stays
        final JButton close = new JButton(Icons.get("close"));
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setFocusable(false);
        close.setToolTipText("Close (Esc); the theme last chosen stays");
        close.getAccessibleContext().setAccessibleName("Close the theme chooser");
        close.addActionListener(e -> close());
        final JPanel top = new JPanel(new BorderLayout());
        // a spacer as wide as the button on the left keeps the toggle in the middle
        top.add(Box.createHorizontalStrut(close.getPreferredSize().width), BorderLayout.WEST);
        top.add(toggle, BorderLayout.CENTER);
        top.add(close, BorderLayout.EAST);
        grid.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XS, Theme.SPACE_XS, Theme.SPACE_XS, Theme.SPACE_XS));
        final JScrollPane scroll = new JScrollPane(grid, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(PREVIEW_H / 4);
        final JPanel content = new JPanel(new BorderLayout(0, Theme.GAP));
        content.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD)));
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        dialog.setContentPane(content);
        keys(dialog.getRootPane());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(final WindowEvent e) {
                // a click elsewhere: the swatch's own click, which deactivates it first, must not open it again; a close of
                // its own deactivates it too, after the dispose on X11, and must leave the swatch's next click alone
                if (!closing) {
                    closedNanos = System.nanoTime();
                }
                dialog.dispose();
            }
        });
        show(lightNow);
        // as wide as three cards, as tall as four rows of them and the toggle; the grid scrolls past that
        final Dimension card = cards.isEmpty() ? new Dimension(PREVIEW_W, PREVIEW_H) : cards.getFirst().getPreferredSize();
        final Insets in = grid.getInsets();
        final int rows = Math.min(ROWS_SHOWN, Math.max(1, (slots(List.of(NamedTheme.values()), false).size() + COLUMNS - 1) / COLUMNS));
        scroll.getViewport().setPreferredSize(new Dimension(COLUMNS * card.width + (COLUMNS - 1) * Theme.SPACE_M + in.left + in.right,
                rows * card.height + (rows - 1) * Theme.SPACE_M + in.top + in.bottom));
        dialog.pack();
    }

    /**
     * Opens the chooser under {@code anchor}, right-aligned with it; {@code chosen} hears the theme picked, after the
     * window closes.
     */
    public static ThemeChooser show(final Component anchor, final Consumer<NamedTheme> chosen) {
        final ThemeChooser c = new ThemeChooser(javax.swing.SwingUtilities.getWindowAncestor(anchor), chosen);
        final Point at = anchor.getLocationOnScreen();
        final Rectangle anchorOnScreen = new Rectangle(at, anchor.getSize());
        c.dialog.setLocation(place(anchorOnScreen, c.dialog.getSize(), screenOf(at)));
        c.dialog.setVisible(true);
        return c;
    }

    JDialog dialog() {
        return dialog;
    }

    /** Whether the chooser is open. */
    boolean open() {
        return dialog.isShowing();
    }

    /** Closes the chooser; the theme last chosen in it stays. */
    void close() {
        closing = true;
        dialog.dispose();
    }

    /** Whether a chooser closed by losing the focus just now: the swatch's click that took the focus. */
    static boolean justClosed() {
        return System.nanoTime() - closedNanos < CLICK_NANOS;
    }

    /**
     * Where a popover of {@code size} goes for {@code anchor}: under it, its right edge on the anchor's, kept on
     * {@code screen} when the anchor is on one; on no screen, as a window off every screen is, it is left where it
     * falls, so that it never shows on a screen the anchor is not on.
     */
    static Point place(final Rectangle anchor, final Dimension size, final Optional<Rectangle> screen) {
        int x = anchor.x + anchor.width - size.width;
        int y = anchor.y + anchor.height + Theme.SPACE_XS;
        if (screen.isPresent()) {
            final Rectangle s = screen.get();
            x = Math.max(s.x, Math.min(x, s.x + s.width - size.width));
            if (y + size.height > s.y + s.height) {
                // no room below: above the anchor instead
                y = Math.max(s.y, anchor.y - Theme.SPACE_XS - size.height);
            }
        }
        return new Point(x, y);
    }

    /** The usable bounds of the screen {@code p} is on, less the menu bar and the Dock; empty when it is on none. */
    private static Optional<Rectangle> screenOf(final Point p) {
        for (final GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            final Rectangle b = d.getDefaultConfiguration().getBounds();
            if (b.contains(p)) {
                final Insets in = Toolkit.getDefaultToolkit().getScreenInsets(d.getDefaultConfiguration());
                return Optional.of(new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom));
            }
        }
        return Optional.empty();
    }

    /**
     * The cards of one mode, in their slots: the pairs first, each theme of the mode at its pair's index, so that a
     * dark theme and its twin share a slot; then the themes of the mode no pair holds, in order.
     */
    static List<NamedTheme> slots(final List<NamedTheme> themes, final boolean onLight) {
        final List<NamedTheme> paired = new ArrayList<>();
        final List<NamedTheme> alone = new ArrayList<>();
        final Set<NamedTheme> taken = new HashSet<>();
        for (final NamedTheme d : themes) {
            if (d.ground.light) {
                continue;
            }
            final Optional<NamedTheme> twin = themes.stream().filter(t -> t.ground.light && !taken.contains(t)
                    && t.twin.map(d.id::equals).orElse(false)).findFirst();
            if (twin.isPresent()) {
                taken.add(twin.get());
                paired.add(onLight ? twin.get() : d);
            } else if (!onLight) {
                alone.add(d);
            }
        }
        if (onLight) {
            themes.stream().filter(t -> t.ground.light && !taken.contains(t)).forEach(alone::add);
        }
        paired.addAll(alone);
        return paired;
    }

    /**
     * Turns the whole window to the mode: the theme in the slot of the theme chosen, its twin, is chosen, with the fade,
     * and the grid shows the mode's cards; the chooser stays open. A theme chosen in no slot, or a slot the mode leaves
     * empty, takes the mode's first theme. The mode shown already changes nothing.
     */
    private void flip(final boolean onLight) {
        if (shownLight == onLight) {
            return;
        }
        flipped(List.of(NamedTheme.values()), NamedTheme.chosen(), onLight).ifPresent(chosen);
        show(onLight);
    }

    /**
     * The theme of the mode {@code onLight} in the slot {@code now} holds in the other: its twin; the mode's first
     * theme when {@code now} is in no slot or the mode leaves that slot empty; none when the mode has no theme.
     */
    static Optional<NamedTheme> flipped(final List<NamedTheme> themes, final Optional<NamedTheme> now, final boolean onLight) {
        final List<NamedTheme> to = slots(themes, onLight);
        if (to.isEmpty()) {
            return Optional.empty();
        }
        final int slot = now.map(slots(themes, !onLight)::indexOf).orElse(-1);
        return Optional.of(slot >= 0 && slot < to.size() ? to.get(slot) : to.getFirst());
    }

    /** Fills the grid with the cards of the mode, the focus on the card in the slot it was in, or the theme chosen. */
    private void show(final boolean onLight) {
        shownLight = onLight;
        final int slot = cards.stream().filter(Component::isFocusOwner).findFirst().map(cards::indexOf).orElse(-1);
        cards.clear();
        grid.removeAll();
        final Optional<NamedTheme> now = NamedTheme.chosen();
        for (final NamedTheme t : slots(List.of(NamedTheme.values()), onLight)) {
            final Card card = new Card(t, now.map(n -> n == t).orElse(false));
            card.addActionListener(e -> {
                chosen.accept(t);
                // the chooser stays open to try the next: the cards again, the ring on the theme just chosen
                show(shownLight);
            });
            cards.add(card);
            grid.add(card);
        }
        grid.revalidate();
        grid.repaint();
        final Card focus = slot >= 0 && slot < cards.size() ? cards.get(slot)
                : cards.stream().filter(c -> c.chosen).findFirst().orElse(cards.isEmpty() ? null : cards.getFirst());
        if (focus != null) {
            focus.requestFocusInWindow();
        }
    }

    /** Esc closes; D and L flip the toggle; the arrows move between cards; ⏎ chooses the card with the focus. */
    private void keys(final JComponent pane) {
        bind(pane, KeyEvent.VK_ESCAPE, "close", this::close);
        bind(pane, KeyEvent.VK_D, "dark", () -> mode.go(false));
        bind(pane, KeyEvent.VK_L, "light", () -> mode.go(true));
        bind(pane, KeyEvent.VK_LEFT, "left", () -> move(-1));
        bind(pane, KeyEvent.VK_RIGHT, "right", () -> move(1));
        bind(pane, KeyEvent.VK_UP, "up", () -> move(-COLUMNS));
        bind(pane, KeyEvent.VK_DOWN, "down", () -> move(COLUMNS));
        bind(pane, KeyEvent.VK_ENTER, "choose", () -> cards.stream().filter(Component::isFocusOwner).findFirst().ifPresent(Card::doClick));
    }

    private static void bind(final JComponent pane, final int key, final String name, final Runnable r) {
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        pane.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                r.run();
            }
        });
    }

    private void move(final int by) {
        final int at = cards.stream().filter(Component::isFocusOwner).findFirst().map(cards::indexOf).orElse(-1);
        final int to = at < 0 ? 0 : Math.clamp(at + by, 0, cards.size() - 1);
        if (to < cards.size()) {
            final Card c = cards.get(to);
            c.requestFocusInWindow();
            c.scrollRectToVisible(new Rectangle(c.getSize()));
        }
    }

    /** One theme's card: its preview over its name, a ring in its tint when it is the theme chosen. */
    static final class Card extends JButton {

        /** The ring's room round the preview. */
        private static final int RING = 3;
        final NamedTheme theme;
        final boolean chosen;

        Card(final NamedTheme theme, final boolean chosen) {
            this.theme = theme;
            this.chosen = chosen;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            getAccessibleContext().setAccessibleName(theme.label);
            setToolTipText(theme.file.map(f -> theme.label + ", from " + f.getFileName()).orElse(theme.label)
                    + (chosen ? "; the theme chosen" : "; click to choose it"));
            final FontMetrics m = getFontMetrics(getFont());
            setPreferredSize(new Dimension(PREVIEW_W + 2 * RING, PREVIEW_H + 2 * RING + Theme.SPACE_XS + m.getHeight()));
        }

        @Override
        protected void paintComponent(final Graphics g0) {
            final Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ThemePreview.paint(g, theme, RING, RING, PREVIEW_W, PREVIEW_H, getFont(), Theme.mono(getFont().getSize2D() - 1f));
                final boolean hover = getModel().isRollover();
                if (chosen || hover || isFocusOwner()) {
                    g.setColor(chosen ? theme.tint.body : Theme.MUTED);
                    g.setStroke(new BasicStroke(chosen ? 2f : 1f));
                    g.draw(new RoundRectangle2D.Float(1, 1, PREVIEW_W + 2 * RING - 2, PREVIEW_H + 2 * RING - 2,
                            Theme.RADIUS + 2, Theme.RADIUS + 2));
                }
                final FontMetrics m = g.getFontMetrics(getFont());
                g.setFont(chosen ? getFont().deriveFont(Font.BOLD) : getFont());
                g.setColor(getForeground());
                g.drawString(theme.label, RING, PREVIEW_H + 2 * RING + Theme.SPACE_XS + m.getAscent());
            } finally {
                g.dispose();
            }
        }
    }
}
