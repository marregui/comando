package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class StatusBarNoticeTest {

    @Test
    void aClickOnANoticeGoesWhereItIsAboutAndItsTooltipSaysSo() throws Exception {
        List<String> went = new CopyOnWriteArrayList<>();
        StatusBar bar = new StatusBar(intent -> {
        }, n -> n.kind().equals("log") ? Optional.of(() -> went.add(n.key())) : Optional.empty());
        Notice stall = new Notice(Notice.Level.WARN, "The window stopped responding for 1.2 s; click to see where in the log", 5L,
                Notice.log(1_234L));
        AppState s = States.twoPanels().withNotices(stall, List.of(stall));
        SwingUtilities.invokeAndWait(() -> bar.render(s));
        assertThat(bar.noticeLabel().getToolTipText()).contains("click to go there, right-click for recent notices");

        SwingUtilities.invokeAndWait(() -> bar.goTo(stall));
        assertThat(went).containsExactly("1234");

        Notice saved = new Notice(Notice.Level.INFO, "Saved notes", 6L);
        SwingUtilities.invokeAndWait(() -> bar.render(s.withNotices(saved, List.of(stall, saved))));
        assertThat(bar.noticeLabel().getToolTipText()).as("nothing to go to: the click opens the recent notices")
                .contains("click for recent notices").doesNotContain("go there");
    }

    @Test
    void aNoticeNamesItsKindAndKey() {
        Notice n = new Notice(Notice.Level.WARN, "x", 1L, Notice.log(42L));
        assertThat(n.kind()).isEqualTo("log");
        assertThat(n.key()).isEqualTo("42");
        assertThat(new Notice(Notice.Level.INFO, "x", 1L, Notice.panel("a:b")).key()).as("a key may hold a colon").isEqualTo("a:b");
        assertThat(Notice.NONE.kind()).isEmpty();
        assertThat(Notice.NONE.key()).isEmpty();
    }
}
