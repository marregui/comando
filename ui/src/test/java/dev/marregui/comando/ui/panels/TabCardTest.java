package dev.marregui.comando.ui.panels;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The card's explaining form (record 125), a dialog field's ⓘ, and the leave grace every card shares.
class TabCardTest {

    /// An explanation is its title, "What it is" and the text, with the close button and nothing to read again; a
    /// panel's card shown next in the same card has its "Summary" and its button to read it again back.
    @Test
    void anExplanationIsItsTitleWhatItIsAndTheTextWithTheCloseButtonAlone() throws Exception {
        final TabCard card = new TabCard();
        final JLabel anchor = anchored();
        SwingUtilities.invokeAndWait(() -> card.explain("name", anchor, new Rectangle(0, 0, 40, 16), "Name", "A session is one line of work."));
        assertThat(card.shown()).isEqualTo("name");
        assertThat(card.statusText()).isEqualTo("Name");
        assertThat(card.headingLabel().getText()).isEqualTo("What it is");
        assertThat(card.topicText()).isEqualTo("A session is one line of work.");
        assertThat(card.detailsText()).as("no facts").isEmpty();
        assertThat(card.refreshButton().isVisible()).as("nothing to read again").isFalse();
        assertThat(card.closeButton().isVisible()).isTrue();

        SwingUtilities.invokeAndWait(() -> card.show("p", anchor, new Rectangle(0, 0, 40, 16), new CardFacts("panel", List.of()), "topic", false, false));
        assertThat(card.headingLabel().getText()).isEqualTo("Summary");
        assertThat(card.refreshButton().isVisible()).isTrue();
        SwingUtilities.invokeAndWait(() -> card.closeButton().doClick());
        assertThat(card.shown()).as("its close button closes it").isNull();
    }

    /// Left by the pointer, what the card hangs under lets it close after the grace, unless the pointer reached the
    /// card or came back.
    @Test
    void leftWithoutReachingItTheCardClosesAfterTheGrace() throws Exception {
        final TabCard card = new TabCard();
        final JLabel anchor = anchored();
        final Rectangle at = new Rectangle(0, 0, 40, 16);
        SwingUtilities.invokeAndWait(() -> {
            card.explain("name", anchor, at, "Name", "text");
            card.left();
        });
        Thread.sleep(TabCard.LEAVE_GRACE_MILLIS + 300);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.shown()).as("drifted away").isNull();

        SwingUtilities.invokeAndWait(() -> {
            card.explain("name", anchor, at, "Name", "text");
            card.left();
            card.component().dispatchEvent(new MouseEvent(card.component(), MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0,
                    false));
        });
        Thread.sleep(TabCard.LEAVE_GRACE_MILLIS + 300);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.shown()).as("the pointer reached the card").isEqualTo("name");

        SwingUtilities.invokeAndWait(() -> {
            card.hide();
            card.explain("name", anchor, at, "Name", "text");
            card.left();
            card.stay();
        });
        Thread.sleep(TabCard.LEAVE_GRACE_MILLIS + 300);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.shown()).as("the pointer came back").isEqualTo("name");
    }

    /// A label in a root pane, laid out, for a card to hang under.
    private static JLabel anchored() throws Exception {
        final JLabel[] l = new JLabel[1];
        SwingUtilities.invokeAndWait(() -> {
            final JRootPane root = new JRootPane();
            l[0] = new JLabel("Name");
            root.getContentPane().add(l[0]);
            root.setSize(800, 600);
            root.doLayout();
            root.getLayeredPane().setSize(800, 600);
        });
        return l[0];
    }
}
