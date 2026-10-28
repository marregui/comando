package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.text.TextLinks;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.LayeredHighlighter;
import javax.swing.text.Position;
import javax.swing.text.View;
import java.awt.Shape;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.theme.TintTitle;
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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A tab's card, in place of its tooltip, and a session row's in the sidebar, the same card: laid out as a turn's report
 * in the transcript: the transcript's ground, a bar of the chosen tint down its left edge; its title, the panel's or
 * the session's name, in the headings' serif along the tint's ramp; its facts (record 90), each said once, a quiet label
 * and the value in the transcript's ivory; under them, set apart,
 * "Summary" in the headings' serif along the tint's ramp over a tinted rule, and the topic in the transcript's ivory,
 * wrapped to a few lines; the Linear tickets the panel's text names, as links a double click opens in the browser; at the bottom
 * left, a button that reads the summary again and one that closes the card. The card closes when the pointer leaves
 * it, at a click on it (not on a link) and at its close button. It floats in the window's popup layer under the tab or row it describes and stays until it is closed or clicked,
 * or until another card takes its place; the window moves it with itself. A card is known by a key: its panel's, a
 * headless session's, or a connection's. A connection's card is its title and facts alone: no summary, no tickets, no
 * buttons (record 92). EDT only.
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
                g.setColor(Theme.ground());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setPaint(Theme.tint().ramp(0, getHeight(), false));
                g.fillRect(0, 0, BAR, getHeight());
            } finally {
                g.dispose();
            }
        }
    };
    /** The panel's or the session's name; text from a session, a document and Haiku is never rendered as HTML. */
    private final TintTitle title;
    /** The facts, a label and a value a row; hidden when there are none. */
    private final JPanel facts = new JPanel(new GridBagLayout());
    private CardFacts head = new CardFacts("", List.of());
    private final Font font;
    private final TintTitle heading;
    /** The tickets, links in a row under the heading; hidden when the text names none. */
    private final JPanel tickets = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    /** The session's delegation (record 109): a line per sub-agent, each opening its tab; hidden when there is none. */
    private final JPanel agents = new JPanel();
    /** Whether the pointer is over the card or one of its parts. */
    private boolean pointerIn;
    private Runnable onPointerIn = () -> { };
    /** Hears the card close: what it hung under, and the point on the screen the pointer left it for, or null. */
    private BiConsumer<JComponent, Point> onHidden = (over, at) -> { };
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
                hide(e.getLocationOnScreen());
            }
        }
    };
    /** A press on the card closes it; its parts that take presses themselves hear it too. */
    private final MouseAdapter closeOnPress = new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
            hide();
        }
    };
    private final JTextArea topic = new JTextArea();
    /** True while the summary is a placeholder, in grey italics, not a summary read. */
    private boolean muted;
    private final JButton refresh = button("refresh", "tab-card-refresh", "Summarise again with Haiku (about 2 s)",
            "Summarise again");
    private final JButton close = button("close", "tab-card-close", "Close the card", "Close the card");
    /** The summary's parts: its heading, its text and the space around them, and the buttons; hidden on a card of facts. */
    private final List<Component> summaryParts = new ArrayList<>();
    /** Whether the card shown has a summary. */
    private boolean summarised = true;
    private String shown;
    private JLayeredPane layer;
    /** Where the card's top left corner goes, in the tabs' coordinates: under the tab, at its left edge. */
    private Point anchor;
    private JComponent tabs;
    /**
     * A row's card: where it goes beside the rows, in the rows' coordinates, its left edge at x and its top at the row's;
     * null for a tab's card, or under the row. It goes under the row when the window has no room beside them.
     */
    private Point besideAt;

    TabCard(Consumer<String> onRefresh) {
        card.setName("tab-card");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(Theme.SPACE_M, Theme.SPACE_M + BAR + Theme.SPACE_XS, Theme.SPACE_S, Theme.SPACE_M)));
        Font tip = UIManager.getFont("ToolTip.font");
        font = tip == null ? new Font(Font.DIALOG, Font.PLAIN, 12) : tip;
        // the report's first heading: the card's name
        title = new TintTitle("", Theme.TextSize.TITLE.pt);
        title.setName("tab-card-title");
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        facts.setName("tab-card-facts");
        facts.setOpaque(false);
        facts.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, 0, 0, 0));
        facts.setVisible(false);
        // the report's second heading, a step above the text
        heading = new TintTitle("Summary", Theme.TextSize.SUBTITLE.pt);
        heading.setName("tab-card-heading");
        topic.setFont(Theme.font(font, Theme.TextSize.BODY));
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
        for (JComponent c : new JComponent[]{title, facts, heading, topic, actions}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        card.add(title);
        card.add(facts);
        agents.setName("tab-card-agents");
        agents.setLayout(new BoxLayout(agents, BoxLayout.Y_AXIS));
        agents.setOpaque(false);
        agents.setAlignmentX(Component.LEFT_ALIGNMENT);
        agents.setVisible(false);
        card.add(agents);
        Component aboveHeading = Box.createVerticalStrut(Theme.SPACE_M);
        Component underHeading = Box.createVerticalStrut(Theme.SPACE_XS);
        Component aboveActions = Box.createVerticalStrut(Theme.SPACE_S);
        card.add(aboveHeading);
        card.add(heading);
        card.add(underHeading);
        tickets.setOpaque(false);
        tickets.setAlignmentX(Component.LEFT_ALIGNMENT);
        tickets.setVisible(false);
        card.add(tickets);
        card.add(topic);
        card.add(aboveActions);
        card.add(actions);
        summaryParts.addAll(List.of(aboveHeading, heading, underHeading, topic, aboveActions, actions));
        card.addMouseListener(closeOnPress);
        // the summary is a text area, which takes the press itself: a press closes the card as on the rest, but on a
        // link, which a double click follows (record 94)
        MouseAdapter summaryLinks = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (topicLinkAt(e.getPoint()) == null) {
                    hide();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                TextLinks.Ref link = topicLinkAt(e.getPoint());
                if (link != null && e.getClickCount() == 2) {
                    follow(link.href());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                topic.setCursor(topicLinkAt(e.getPoint()) == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
        };
        topic.addMouseListener(summaryLinks);
        topic.addMouseMotionListener(summaryLinks);
        hearPointer(card);
        paintTint();
        // one card for the window's life: it hears every tint chosen
        Theme.onTint(this::paintTint);
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

    /**
     * {@code r} hears the card close: the component it hung under, and the point on the screen the pointer left it for,
     * or null when it closed otherwise.
     */
    void onHidden(BiConsumer<JComponent, Point> r) {
        onHidden = r;
    }

    void browseWith(Consumer<String> opener) {
        browse = opener;
    }

    boolean pointerIn() {
        return pointerIn;
    }

    /**
     * The session's sub-agents to outline under the facts (record 109), each a link that a double click follows to its
     * tab through {@code open}, given the Agent call's id; none hides the outline.
     */
    void agents(List<Delegation.Line> list, Consumer<String> open) {
        List<String> now = list.stream().map(Delegation.Line::text).toList();
        if (now.equals(agentTexts)) {
            agentOpen = open;
            return;
        }
        agentTexts = now;
        agentOpen = open;
        agents.removeAll();
        Font font = topic.getFont();
        if (!list.isEmpty()) {
            agents.add(Box.createVerticalStrut(Theme.SPACE_S));
            JLabel head = Plain.only(new JLabel("Sub-agents"));
            head.setFont(font);
            head.setForeground(Theme.MUTED);
            agents.add(head);
        }
        for (Delegation.Line line : list) {
            JLabel link = Plain.only(new JLabel(line.text()));
            link.setName("tab-card-agent");
            link.setFont(font.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)));
            link.setForeground(line.status() == Block.ToolStatus.ERROR ? Theme.DANGER : Theme.LINK);
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.setToolTipText("Double click to open the sub-agent's tab");
            link.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        agentOpen.accept(line.toolUseId());
                    }
                }
            });
            agents.add(link);
        }
        for (Component c : agents.getComponents()) {
            c.addMouseListener(pointer);
        }
        agents.setVisible(!list.isEmpty());
        if (shown != null) {
            lastText = null;
            place();
        }
    }

    /** The outline's lines shown, to build it again only when they change. */
    private List<String> agentTexts = List.of();
    private Consumer<String> agentOpen = id -> { };

    /** The outline's links; for tests. */
    List<JLabel> agentLinks() {
        return Arrays.stream(agents.getComponents()).filter(c -> "tab-card-agent".equals(c.getName())).map(c -> (JLabel) c).toList();
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
            link.setForeground(Theme.LINK);
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

    /** The links in the summary shown: web addresses, notes, Markdown links, as {@link TextLinks} finds them. */
    private List<TextLinks.Ref> topicLinks = List.of();

    private void underlineLinks(String text) {
        topicLinks = TextLinks.refs(text, true);
        Highlighter h = topic.getHighlighter();
        h.removeAllHighlights();
        for (TextLinks.Ref r : topicLinks) {
            try {
                h.addHighlight(r.start(), r.end(), UNDERLINE);
            } catch (BadLocationException e) {
                // the text changed under it: the next update underlines again
            }
        }
    }

    /** Underlines a link in the summary in the links' colour. */
    private static final Highlighter.HighlightPainter UNDERLINE = new LayeredHighlighter.LayerPainter() {
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            paintLayer(g, p0, p1, bounds, c, null);
        }

        @Override
        public Shape paintLayer(Graphics g, int p0, int p1, Shape bounds, JTextComponent c, View view) {
            try {
                Rectangle r = view == null ? bounds.getBounds() : view.modelToView(p0, Position.Bias.Forward, p1, Position.Bias.Backward, bounds).getBounds();
                g.setColor(Theme.LINK);
                int y = r.y + r.height - 2;
                g.drawLine(r.x, y, r.x + r.width - 1, y);
                return r;
            } catch (BadLocationException e) {
                return null;
            }
        }
    };

    /** The link in the summary under {@code p}, in the summary's coordinates; null when there is none. */
    TextLinks.Ref topicLinkAt(Point p) {
        int at = topic.viewToModel2D(p);
        for (TextLinks.Ref r : topicLinks) {
            if (at >= r.start() && at < r.end()) {
                return r;
            }
        }
        return null;
    }

    /** Follows a link of the card: a web page in the browser, a file in a panel. */
    private void follow(String href) {
        if (Browser.isWebLink(href)) {
            browse.accept(href);
        } else {
            Links.open(href, null);
        }
    }

    /** The links in the summary shown; for tests. */
    List<TextLinks.Ref> topicLinks() {
        return topicLinks;
    }

    /** The tickets shown, for tests. */
    List<JLabel> ticketLinks() {
        return Arrays.stream(tickets.getComponents()).filter(c -> "tab-card-ticket".equals(c.getName())).map(c -> (JLabel) c).toList();
    }

    /** Shows the card of {@code key} under {@code tab}, a rectangle of {@code over}, replacing any card shown. */
    void show(String key, JComponent over, Rectangle tab, CardFacts head, String topicText, boolean muted, boolean reading) {
        show(key, over, tab, -1, head, topicText, muted, reading);
    }

    /**
     * As {@link #show(String, JComponent, Rectangle, CardFacts, String, boolean, boolean)}, a row's card: beside the
     * rows, its left edge at {@code beside} and its top at the row's, when the window has room; under the row otherwise,
     * or when {@code beside} is negative.
     */
    void show(String key, JComponent over, Rectangle row, int beside, CardFacts head, String topicText, boolean muted, boolean reading) {
        summarised(true);
        open(key, over, row, beside, head, topicText, muted, reading);
    }

    /** As {@link #show}, a card of its title and facts alone: a connection's. Its tickets are cleared. */
    void showFacts(String key, JComponent over, Rectangle row, int beside, CardFacts head) {
        summarised(false);
        tickets(List.of());
        agents(List.of(), id -> { });
        open(key, over, row, beside, head, "", false, false);
    }

    private void summarised(boolean on) {
        summarised = on;
        summaryParts.forEach(c -> c.setVisible(on));
    }

    /** Whether the card shown has a summary; for tests. */
    boolean summarised() {
        return summarised;
    }

    private void open(String key, JComponent over, Rectangle tab, int beside, CardFacts head, String topicText, boolean muted, boolean reading) {
        JRootPane root = SwingUtilities.getRootPane(over);
        if (root == null) {
            return;
        }
        if (layer != root.getLayeredPane()) {
            hide();
            layer = root.getLayeredPane();
        }
        shown = key;
        tabs = over;
        anchor = new Point(tab.x, tab.y + tab.height + 2);
        besideAt = beside < 0 ? null : new Point(beside, tab.y);
        if (card.getParent() != layer) {
            layer.add(card, JLayeredPane.POPUP_LAYER);
        }
        // shown anew: placed even when its text is the last card's
        lastText = null;
        update(head, topicText, muted, reading);
    }

    /** The text the card shows now, to size and place it again only when it changed. */
    private List<Object> lastText;

    /** New text for the card shown, sized and placed again when it changed; nothing when none shows. */
    void update(CardFacts head, String topicText, boolean muted, boolean reading) {
        if (shown == null) {
            return;
        }
        List<Object> text = List.of(head, topicText, muted, reading);
        if (text.equals(lastText)) {
            return;
        }
        lastText = text;
        title.setText(Plain.text(head.title()));
        if (!head.facts().equals(this.head.facts())) {
            fill(head.facts());
        }
        this.head = head;
        topic.setText(topicText);
        underlineLinks(topicText);
        Font f = topic.getFont();
        topic.setFont(f.deriveFont(muted ? Font.ITALIC : Font.PLAIN));
        this.muted = muted;
        paintTint();
        refresh.setEnabled(!reading);
        place();
    }

    /**
     * The facts as rows: the label in a quiet grey, a step below, the value in the transcript's ivory, clipped at the
     * card's edge rather than wrapped.
     */
    private void fill(List<CardFacts.Fact> list) {
        facts.removeAll();
        Font label = Theme.font(font, Theme.TextSize.SMALL).deriveFont(Map.of(TextAttribute.TRACKING, 0.04f));
        GridBagConstraints at = new GridBagConstraints();
        at.anchor = GridBagConstraints.BASELINE_LEADING;
        at.insets = new Insets(1, 0, 1, 0);
        for (int i = 0; i < list.size(); i++) {
            CardFacts.Fact f = list.get(i);
            JLabel name = Plain.only(new JLabel(f.label()));
            name.setFont(label);
            name.setForeground(Theme.MUTED);
            JLabel value = Plain.only(new JLabel(Plain.text(f.value())));
            value.setName("tab-card-fact");
            value.setFont(font);
            value.setForeground(Theme.TRANSCRIPT_TEXT);
            at.gridy = i;
            at.gridx = 0;
            at.weightx = 0;
            at.fill = GridBagConstraints.NONE;
            at.insets = new Insets(1, 0, 1, Theme.SPACE_L);
            facts.add(name, at);
            at.gridx = 1;
            at.weightx = 1;
            at.fill = GridBagConstraints.HORIZONTAL;
            at.insets = new Insets(1, 0, 1, 0);
            facts.add(value, at);
        }
        for (Component c : facts.getComponents()) {
            c.addMouseListener(pointer);
            c.addMouseListener(closeOnPress);
        }
        facts.setVisible(!list.isEmpty());
        facts.setMaximumSize(new Dimension(Integer.MAX_VALUE, facts.getPreferredSize().height));
    }

    private void paintTint() {
        Tint tint = Theme.tint();
        heading.setForeground(tint.body);
        heading.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, tint.headingRule()),
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
     * Sizes the card: as wide as the title, the facts or the summary's width, whichever is widest, within the window; the
     * summary wraps to that width, the facts clip at it, and the card is as tall as their lines.
     */
    private void place() {
        Point at = SwingUtilities.convertPoint(tabs, anchor, layer);
        int room = Math.max(160, layer.getWidth() - 2 * MARGIN);
        Insets in = card.getInsets();
        // a card of facts is as wide as they are; a summary takes its own width
        int widest = Math.max(Math.max(title.getPreferredSize().width, facts.isVisible() ? facts.getPreferredSize().width : 0),
                summarised ? SUMMARY_WIDTH : 0);
        int inner = Math.min(widest, Math.min(MAX_WIDTH, room) - in.left - in.right);
        wrapTo(topic, inner);
        int w = inner + in.left + in.right;
        card.invalidate(); // the box layout keeps its children's sizes until told: the summary's lines changed
        int h = card.getPreferredSize().height;
        int x = Math.max(MARGIN, Math.min(at.x, layer.getWidth() - w - MARGIN));
        int y = at.y;
        if (besideAt != null) {
            // beside the rows when it fits there whole, its top at the row's, raised to stay in the window
            Point b = SwingUtilities.convertPoint(tabs, besideAt, layer);
            if (b.x + w <= layer.getWidth() - MARGIN) {
                x = b.x;
                y = Math.max(MARGIN, Math.min(b.y, layer.getHeight() - h - MARGIN));
            }
        }
        card.setBounds(x, y, w, h);
        card.validate();
        layer.repaint();
    }

    /** {@code area} wraps to {@code width} and is as tall as its lines. */
    private static void wrapTo(JTextArea area, int width) {
        area.setPreferredSize(null);
        area.setMaximumSize(null);
        area.setSize(width, Short.MAX_VALUE);
        int height = area.getPreferredSize().height;
        area.setPreferredSize(new Dimension(width, height));
        area.setMaximumSize(new Dimension(width, height));
    }

    void hide() {
        hide(null);
    }

    /** Closes the card; {@code at}, the point on the screen the pointer left it for, or null. */
    private void hide(Point at) {
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
        onHidden.accept(tabs, at);
    }

    /** The key of the card shown: its panel's, or a headless session's; null when none shows. */
    String shown() {
        return shown;
    }

    JComponent component() {
        return card;
    }

    /** What the card shown hangs under: the tabs, or the sidebar's sessions; null when none shows. */
    JComponent over() {
        return shown == null ? null : tabs;
    }

    /** The title shown; for tests. */
    String statusText() {
        return title.getText();
    }

    /** The facts shown, "Label: value" a line; for tests. */
    String detailsText() {
        return head.text();
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
    TintTitle headingLabel() {
        return heading;
    }

    JTextArea topicArea() {
        return topic;
    }
}
