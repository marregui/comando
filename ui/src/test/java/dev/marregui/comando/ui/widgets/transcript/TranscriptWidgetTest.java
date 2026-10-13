package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptWidgetTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final TranscriptWidget widget = new TranscriptWidget(new WidgetContext(intent -> { }, new ActionRegistry()));

    private static void fill(Transcript t, int from, int to) {
        var steps = SyntheticSession.steps(24);
        for (int i = from; i < to && i < steps.size(); i++) {
            if (steps.get(i).index() < 0) {
                t.append(steps.get(i).block());
            }
        }
    }

    @Test
    void aTranscriptLoadedWhileHiddenKeepsFollowingTheEnd() {
        // a restored session: the transcript is loaded while the Session tab is hidden, then a new turn arrives
        Transcript t = new Transcript();
        fill(t, 0, 40);
        widget.render(new TranscriptView("s", t));
        assertThat(widget.component().isShowing()).isFalse();
        assertThat(widget.following()).isTrue();

        fill(t, 40, 80);
        widget.render(new TranscriptView("s", t));
        assertThat(widget.following()).as("an unlaid-out scroll bar reads 'at the top' and must not stop following").isTrue();
    }

    @Test
    void anotherTranscriptStartsFollowingAgain() {
        Transcript first = new Transcript();
        fill(first, 0, 40);
        widget.render(new TranscriptView("a", first));
        widget.jumpToLastPrompt();
        assertThat(widget.following()).isFalse();

        Transcript second = new Transcript();
        fill(second, 0, 10);
        widget.render(new TranscriptView("b", second));
        assertThat(widget.following()).isTrue();
    }
}
