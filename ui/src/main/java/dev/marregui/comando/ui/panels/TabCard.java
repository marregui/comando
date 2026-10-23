package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Gold;
import dev.marregui.comando.ui.kit.theme.GoldTitle;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A tab's card, in place of its tooltip, laid out as a turn's report in the transcript: the ground lifted a little,
 * a bar of the chosen gold down its left edge; what the tooltip said, in grey; under it, set apart, "Summary" in the
 * headings' serif along the gold's ramp over a gold rule, and the panel's topic in the transcript's ivory, wrapped to
 * a few lines; the Linear tickets the panel's text names, as links a double click opens in the browser; at the bottom
 * left, a button that reads the summary again and one that closes the card. The card closes when the pointer leaves
 * it, at a click on it (not on a link) and at its close button. It floats in the window's popup layer under the tab it describes and stays until it is closed or clicked, or
 * until another tab's card takes its place; the window moves it with itself. EDT only.
 */
final class TabCard {

    static final int MAX_WIDTH = 640;
    /** The summary's width: about 70 characters a line, so a summary of 300 takes four lines at most. */
    static final int SUMMARY_WIDTH = 460;
    private static final int MARGIN = 8;

    /** The bar's width, as the report's. */
    private static final int BAR = 3;
    private final JPanel card = new JPanel() {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                Gold gold = Theme.gold();
                g.setColor(Theme.tooltipGround());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setPaint(gold.ramp(0, getHeight(), false));
                g.fillRect(0, 0, BAR, getHeight());
            } finally {
                g.dispose();
            }
        }
    };
    /** Text from a session, a document and Haiku: never rendered as HTML. */
    private final JLabel status = Plain.only(new JLabel());
    private final GoldTitle heading;
    /** The tickets, links in a row under the heading; hidden when the text names none. */
    private final JPanel tickets = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    /** Whether the pointer is over the card or one of its parts. */
    private boolean pointerIn;
    private Runnable onPointerIn = () -> { };
    /** Opens a ticket's address: the desktop's browser; tests hear it instead. */
    private Consumer<String> browse = Browser::open;
    /** Hears the pointer on the card and every part of it: entering, and leaving the card as a whole. */
    private final MouseAdapter pointer = new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
            pointerIn = true;
            onPointerIn.run();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            // leaving a part for another part, or for the card's own ground, is not leaving the card
            Point at = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), card);
            if (!new Rectangle(card.getSize()).contains(at)) {
                pointerIn = false;
                hide();
            }
        }
    };
    private final JTextArea topic = new JTextArea();
    /** True while the summary is a placeholder, in grey italics, not a summary read. */
    private boolean muted;
    private final JButton refresh = button("refresh", "tab-card-refresh", "Summarise the panel again with Haiku (about 2 s)",
            "Summarise again");
    private final JButton close = button("close", "tab-card-close", "Close the card", "Close the card");
    private PanelId shown;
    private JLayeredPane layer;
    /** Where the card's top left corner goes, in the tabs' coordinates: under the tab, at its left edge. */
    private Point anchor;
    private JComponent tabs;

    TabCard(Consumer<PanelId> onRefresh) {
        card.setName("tab-card");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(10, 10 + BAR + 4, 6, 12)));
        Font font = UIManager.getFont("ToolTip.font");
        if (font == null) {
            font = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        status.setFont(font);
        status.setForeground(Theme.MUTED_STRONG);
        // the report's second heading, a size above the text
        heading = new GoldTitle("Summary", font.getSize2D() * 1.25f);
        heading.setName("tab-card-heading");
        topic.setFont(font.deriveFont(font.getSize2D() + 0.5f));
        topic.setName("tab-card-topic");
        topic.setEditable(false);
        topic.setFocusable(false);
        topic.setOpaque(false);
        topic.setLineWrap(true);
        topic.setWrapStyleWord(true);
        topic.setBorder(BorderFactory.createEmptyBorder());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(refresh);
        actions.add(close);
        refresh.addActionListener(e -> {
            if (shown != null) {
                onRefresh.accept(shown);
            }
        });
        close.addActionListener(e -> hide());
        for (JComponent c : new JComponent[]{status, heading, topic, actions}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        card.add(status);
        card.add(Box.createVerticalStrut(10));
        card.add(heading);
        card.add(Box.createVerticalStrut(6));
        tickets.setOpaque(false);
        tickets.setAlignmentX(Component.LEFT_ALIGNMENT);
        tickets.setVisible(false);
        card.add(tickets);
        card.add(topic);
        card.add(Box.createVerticalStrut(6));
        card.add(actions);
        MouseAdapter closeOnPress = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                hide();
            }
        };
        card.addMouseListener(closeOnPress);
        // the summary is a text area, which takes the press itself: a press on it closes the card as on the rest
        topic.addMouseListener(closeOnPress);
        hearPointer(card);
        paintGold();
        // one card for the window's life: it hears every gold chosen
        Theme.onGold(this::paintGold);
        // the rule under the heading runs the card's width, as under a report's heading
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
    }

    private static JButton button(String icon, String name, String tip, String accessible) {
        JButton b = new JButton(Icons.get(icon, 14));
        b.setName(name);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(accessible);
        // the keyboard stays where it was: the card is looked at, not typed into
        b.setFocusable(false);
        return b;
    }

    /** {@code c} and every part of it tell the card where the pointer is. */
    private void hearPointer(Component c) {
        c.addMouseListener(pointer);
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                hearPointer(child);
            }
        }
    }

    /** {@code r} runs when the pointer comes onto the card. */
    void onPointerIn(Runnable r) {
        onPointerIn = r;
    }

    void browseWith(Consumer<String> opener) {
        browse = opener;
    }

    boolean pointerIn() {
        return pointerIn;
    }

    /** The tickets to show under the heading, the newest command's first; none hides the row. */
    void tickets(List<LinearTickets.Ticket> list) {
        tickets.removeAll();
        Font font = topic.getFont();
        for (int i = 0; i < list.size(); i++) {
            LinearTickets.Ticket t = list.get(i);
            if (i > 0) {
                JLabel dot = Plain.only(new JLabel(" · "));
                dot.setFont(font);
                dot.setForeground(Theme.MUTED);
                tickets.add(dot);
            }
            JLabel link = Plain.only(new JLabel(t.id()));
            link.setName("tab-card-ticket");
            link.setFont(font.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)));
            link.setForeground(Theme.ACCENT);
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.setToolTipText("Double click to open " + t.id() + " in the browser");
            link.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        browse.accept(t.url());
                    }
                }
            });
            tickets.add(link);
        }
        for (Component c : tickets.getComponents()) {
            c.addMouseListener(pointer);
        }
        tickets.setVisible(!list.isEmpty());
        tickets.setMaximumSize(new Dimension(Integer.MAX_VALUE, tickets.getPreferredSize().height + 4));
        tickets.setBorder(BorderFactory.createEmptyBorder(0, 0, list.isEmpty() ? 0 : 4, 0));
        if (shown != null) {
            place();
        }
    }

    /** The tickets shown, for tests. */
    List<JLabel> ticketLinks() {
        return Arrays.stream(tickets.getComponents()).filter(c -> "tab-card-ticket".equals(c.getName())).map(c -> (JLabel) c).toList();
    }

    /** Shows the card of {@code id} under {@code tab}, a rectangle of {@code over}, replacing any card shown. */
    void show(PanelId id, JComponent over, Rectangle tab, String statusText, String topicText, boolean muted, boolean reading) {
        JRootPane root = SwingUtilities.getRootPane(over);
        if (root == null) {
            return;
        }
        if (layer != root.getLayeredPane()) {
            hide();
            layer = root.getLayeredPane();
        }
        shown = id;
        tabs = over;
        anchor = new Point(tab.x, tab.y + tab.height + 2);
        if (card.getParent() != layer) {
            layer.add(card, JLayeredPane.POPUP_LAYER);
        }
        update(statusText, topicText, muted, reading);
    }

    /** New text for the card shown, sized and placed again; nothing when none shows. */
    void update(String statusText, String topicText, boolean muted, boolean reading) {
        if (shown == null) {
            return;
        }
        status.setText(Plain.text(statusText));
        topic.setText(topicText);
        Font f = topic.getFont();
        topic.setFont(f.deriveFont(muted ? Font.ITALIC : Font.PLAIN));
        this.muted = muted;
        paintGold();
        refresh.setEnabled(!reading);
        place();
    }

    private void paintGold() {
        Gold gold = Theme.gold();
        heading.setForeground(gold.body);
        heading.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Gold.over(Theme.TRANSCRIPT, gold.shade, 0.7f)),
                BorderFactory.createEmptyBorder(0, 0, 4, 0)));
        topic.setForeground(muted ? Theme.MUTED : Theme.TRANSCRIPT_TEXT);
        card.repaint();
    }

    /** Puts the card shown under {@code tab} again, a rectangle of the tabs it was shown over: the tab moved. */
    void moveUnder(Rectangle tab) {
        if (shown == null) {
            return;
        }
        Point now = new Point(tab.x, tab.y + tab.height + 2);
        if (!now.equals(anchor)) {
            anchor = now;
            place();
        }
    }

    /**
     * Sizes the card: as wide as the status line or the summary's width, whichever is wider, within the window; the
     * summary wraps to that width, and the card is as tall as its lines.
     */
    private void place() {
        Point at = SwingUtilities.convertPoint(tabs, anchor, layer);
        int room = Math.max(160, layer.getWidth() - 2 * MARGIN);
        Insets in = card.getInsets();
        int inner = Math.min(Math.max(status.getPreferredSize().width, SUMMARY_WIDTH), Math.min(MAX_WIDTH, room) - in.left - in.right);
        topic.setPreferredSize(null);
        topic.setMaximumSize(null);
        topic.setSize(inner, Short.MAX_VALUE);
        int topicHeight = topic.getPreferredSize().height;
        topic.setPreferredSize(new Dimension(inner, topicHeight));
        topic.setMaximumSize(new Dimension(inner, topicHeight));
        int w = inner + in.left + in.right;
        card.invalidate(); // the box layout keeps its children's sizes until told: the summary's lines changed
        int h = card.getPreferredSize().height;
        int x = Math.max(MARGIN, Math.min(at.x, layer.getWidth() - w - MARGIN));
        card.setBounds(x, at.y, w, h);
        card.validate();
        layer.repaint();
    }

    void hide() {
        if (shown == null) {
            return;
        }
        shown = null;
        pointerIn = false;
        if (layer != null && card.getParent() == layer) {
            Rectangle was = card.getBounds();
            layer.remove(card);
            layer.repaint(was);
        }
    }

    /** The panel whose card shows, null when none does. */
    PanelId shown() {
        return shown;
    }

    JComponent component() {
        return card;
    }

    String statusText() {
        return status.getText();
    }

    String topicText() {
        return topic.getText();
    }

    JButton refreshButton() {
        return refresh;
    }

    JButton closeButton() {
        return close;
    }

    /** The summary's own component, which wraps it; for tests. */
    GoldTitle headingLabel() {
        return heading;
    }

    JTextArea topicArea() {
        return topic;
    }
}
