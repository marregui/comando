package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.ThemePreview;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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

/**
 * The theme chooser (record 128): a window of cards, the dark themes in one column and the light in the other, each
 * light theme on the row of the dark one it is the twin of; themes without a twin, the user's files among them, below.
 * Each card draws its theme small in its own colours ({@link ThemePreview}) over its name; the theme chosen has a ring
 * in its tint. A click, or ⏎ on the card with the keyboard, chooses the theme and closes the window; Esc, or the
 * window losing the focus, closes it with the theme as it was. The window only shows themes: the window behind
 * changes when one is chosen. EDT only.
 */
public final class ThemeChooser {

    public static final String TITLE = "Theme";
    /** A card's preview, in points. */
    static final int PREVIEW_W = 188;
    static final int PREVIEW_H = 96;

    private final JDialog dialog;
    private final List<Card> cards = new ArrayList<>();

    private ThemeChooser(Window owner, Consumer<NamedTheme> chosen) {
        dialog = new JDialog(owner, TITLE);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Optional<NamedTheme> now = NamedTheme.chosen();
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(Theme.SPACE_XS, Theme.SPACE_S, Theme.SPACE_XS, Theme.SPACE_S);
        at.gridy = 0;
        for (String head : new String[]{"Dark", "Light"}) {
            JLabel l = new JLabel(head, SwingConstants.LEFT);
            l.setFont(Theme.font(l.getFont(), Theme.TextSize.SUBTITLE).deriveFont(Font.BOLD));
            at.gridx = head.equals("Dark") ? 0 : 1;
            at.anchor = GridBagConstraints.WEST;
            grid.add(l, at);
        }
        for (NamedTheme[] row : rows(List.of(NamedTheme.values()))) {
            at.gridy++;
            for (int col = 0; col < 2; col++) {
                at.gridx = col;
                if (row[col] == null) {
                    grid.add(new JLabel(), at);
                    continue;
                }
                NamedTheme t = row[col];
                Card card = new Card(t, now.map(n -> n == t).orElse(false));
                card.addActionListener(e -> {
                    dialog.dispose();
                    chosen.accept(t);
                });
                cards.add(card);
                grid.add(card, at);
            }
        }
        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(PREVIEW_H / 4);
        dialog.setContentPane(scroll);
        JComponent pane = dialog.getRootPane();
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        pane.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                dialog.dispose();
            }
        });
        dialog.pack();
        // never taller than most of the screen: the cards scroll
        int most = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.85);
        if (dialog.getHeight() > most) {
            dialog.setSize(new Dimension(dialog.getWidth() + scroll.getVerticalScrollBar().getPreferredSize().width, most));
        }
        dialog.setLocationRelativeTo(owner);
        cards.stream().filter(c -> c.chosen).findFirst().or(() -> cards.stream().findFirst()).ifPresent(Card::requestFocusInWindow);
    }

    /** Opens the chooser over {@code owner}; {@code chosen} hears the theme picked, after the window closes. */
    public static ThemeChooser show(Window owner, Consumer<NamedTheme> chosen) {
        ThemeChooser c = new ThemeChooser(owner, chosen);
        c.dialog.setVisible(true);
        return c;
    }

    JDialog dialog() {
        return dialog;
    }

    /**
     * The chooser's rows, {dark, light}, either may be null: each dark theme in order with the light theme whose twin
     * it is, then each light theme no dark one claimed, in order.
     */
    static List<NamedTheme[]> rows(List<NamedTheme> themes) {
        List<NamedTheme[]> rows = new ArrayList<>();
        Set<NamedTheme> placed = new HashSet<>();
        for (NamedTheme dark : themes) {
            if (dark.ground.light) {
                continue;
            }
            NamedTheme twin = themes.stream().filter(t -> t.ground.light && !placed.contains(t) && t.twin.map(dark.id::equals).orElse(false))
                    .findFirst().orElse(null);
            if (twin != null) {
                placed.add(twin);
            }
            rows.add(new NamedTheme[]{dark, twin});
        }
        for (NamedTheme light : themes) {
            if (light.ground.light && !placed.contains(light)) {
                rows.add(new NamedTheme[]{null, light});
            }
        }
        return rows;
    }

    /** One theme's card: its preview over its name, a ring in its tint when it is the theme chosen. */
    static final class Card extends JButton {

        final NamedTheme theme;
        final boolean chosen;

        Card(NamedTheme theme, boolean chosen) {
            this.theme = theme;
            this.chosen = chosen;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            getAccessibleContext().setAccessibleName(theme.label);
            setToolTipText(theme.file.map(f -> theme.label + ", from " + f.getFileName()).orElse(theme.label)
                    + (chosen ? "; the theme chosen" : "; click to choose it"));
            FontMetrics m = getFontMetrics(getFont());
            setPreferredSize(new Dimension(PREVIEW_W + 2 * RING, PREVIEW_H + 2 * RING + Theme.SPACE_XS + m.getHeight()));
        }

        /** The ring's room round the preview. */
        private static final int RING = 3;

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ThemePreview.paint(g, theme, RING, RING, PREVIEW_W, PREVIEW_H, getFont(), Theme.mono(getFont().getSize2D() - 1f));
                boolean hover = getModel().isRollover();
                if (chosen || hover || isFocusOwner()) {
                    g.setColor(chosen ? theme.tint.body : Theme.MUTED);
                    g.setStroke(new BasicStroke(chosen ? 2f : 1f));
                    g.draw(new RoundRectangle2D.Float(1, 1, PREVIEW_W + 2 * RING - 2, PREVIEW_H + 2 * RING - 2,
                            Theme.RADIUS + 2, Theme.RADIUS + 2));
                }
                FontMetrics m = g.getFontMetrics(getFont());
                g.setFont(chosen ? getFont().deriveFont(Font.BOLD) : getFont());
                g.setColor(getForeground());
                g.drawString(theme.label, RING, PREVIEW_H + 2 * RING + Theme.SPACE_XS + m.getAscent());
            } finally {
                g.dispose();
            }
        }
    }
}
