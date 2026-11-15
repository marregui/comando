package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Optional;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionEta;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionBar]'s ETA: when the session says its step and its task's goal end.
/// - each part shows its time in UTC and the minutes left, rounded up; a passed one says how late, in attention colour
/// - a part without a time is left out; no estimate shows nothing
/// - the bar shows it for a stopped session too, and hides it for an estimate cleared
class SessionBarEtaTest {

    /// 2026-11-15 11:00:00 UTC.
    private static final long NOW_MILLIS = 1_763_204_400_000L;

    /// A step due in 7 minutes and a millisecond reads 8 minutes; a goal 3 minutes past reads "late"; no estimate, no text.
    @Test
    void eachPartShowsItsUtcTimeAndWhatIsLeft() {
        final SessionEta both = new SessionEta("build", NOW_MILLIS + 7 * 60_000L + 1L, "shipped", NOW_MILLIS + 30 * 60_000L);
        assertThat(SessionBar.etaText(both, NOW_MILLIS))
                .isEqualTo("step build · ETA 11:07 UTC (8 min) · goal shipped · ETA 11:30 UTC (30 min)");
        assertThat(SessionBar.late(both, NOW_MILLIS)).isFalse();
        assertThat(SessionBar.etaText(new SessionEta("", 0L, "shipped", NOW_MILLIS - 3 * 60_000L), NOW_MILLIS))
                .isEqualTo("goal shipped · ETA 10:57 UTC (3 min late)");
        assertThat(SessionBar.late(new SessionEta("", 0L, "shipped", NOW_MILLIS - 1L), NOW_MILLIS)).isTrue();
        assertThat(SessionBar.etaText(SessionEta.NONE, NOW_MILLIS)).isEmpty();
    }

    /// A stopped session's estimate shows, muted, with the minutes left; cleared, the label hides.
    @Test
    void theBarShowsTheEstimateAndHidesItOnceCleared() throws Exception {
        final PanelId id = PanelId.random();
        final SessionState stopped = SessionState.closed(SessionSpec.fresh("work", "/w"))
                .withEta(new SessionEta("build", System.currentTimeMillis() + 600_000L, "", 0L));
        final JLabel[] eta = new JLabel[1];
        final boolean[] shown = new boolean[2];
        final String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            final SessionBar bar = new SessionBar(_ -> { });
            final AppState s = AppState.initial(List.of(new PanelState(id, "work", "", "work")), List.of(), List.of(stopped));
            bar.render(s);
            eta[0] = label(bar);
            shown[0] = eta[0].isVisible();
            text[0] = eta[0].getText();
            bar.render(s.withSession("work", x -> x.withStatus(Status.OPEN, "").withEta(SessionEta.NONE)));
            shown[1] = eta[0].isVisible();
        });
        assertThat(shown).containsExactly(true, false);
        assertThat(text[0]).startsWith("step build · ETA ").endsWith(" UTC (10 min)");
        assertThat(eta[0].getForeground()).isEqualTo(Theme.MUTED);
    }

    private static JLabel label(final SessionBar bar) {
        return find(bar.component()).orElseThrow();
    }

    private static Optional<JLabel> find(final Component c) {
        if (c instanceof JLabel l && "ETA".equals(l.getAccessibleContext().getAccessibleName())) {
            return Optional.of(l);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final Optional<JLabel> hit = find(child);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        return Optional.empty();
    }
}
