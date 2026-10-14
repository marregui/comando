package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatusBarUsageTest {

    private static final PlanUsage READ = new PlanUsage(List.of(
            new PlanUsage.Window("Session", 5, "Oct 14 at 7:19pm (Europe/Madrid)"),
            new PlanUsage.Window("Week", 43, "Oct 15 at 1:59pm (Europe/Madrid)"),
            new PlanUsage.Window("Fable", 17, "Oct 15 at 1:59pm (Europe/Madrid)")), 1L, "");

    @Test
    void theBarShowsEachWindowAndWhenTheSessionResets() {
        assertThat(StatusBar.usageText(READ)).isEqualTo("Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(StatusBar.resetTime("Oct 15 at 1:59pm (Europe/Madrid)")).isEqualTo("1:59pm");
        assertThat(StatusBar.resetTime("in 3 hours")).isEmpty();
    }

    @Test
    void aFailedReadKeepsTheLastNumbersMutedAndSaysWhy() throws Exception {
        StatusBar bar = new StatusBar(intent -> {
        });
        JLabel label = bar.usageLabel();
        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed("not logged in", 2L)));
        assertThat(label.isVisible()).as("nothing to show yet").isFalse();

        SwingUtilities.invokeAndWait(() -> bar.planUsage(READ));
        assertThat(label.isVisible()).isTrue();
        assertThat(label.getText()).isEqualTo("Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(label.getToolTipText()).contains("Fable: 17% used, resets Oct 15 at 1:59pm (Europe/Madrid)").doesNotContain("failed");

        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed("<b>timeout</b>", 3L)));
        assertThat(label.getText()).as("the last good read stays").isEqualTo("Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(label.getForeground()).isEqualTo(Theme.MUTED);
        assertThat(label.getToolTipText()).contains("failed: &lt;b&gt;timeout&lt;/b&gt;");
    }

    @Test
    void theSessionItemShowsTheContextAndNoCost() {
        SessionState x = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").withModel("opus").withEffort("high"))
                .withUsage(4.12, 412_000, 1_000_000, 1L);
        assertThat(StatusBar.sessionText(x)).isEqualTo("edge · opus · high · ctx " + Labels.tokens(412_000) + " (41%)").doesNotContain("$");
    }

    @Test
    void aWindowNearItsLimitTurnsTheLabelToAWarningThenADanger() throws Exception {
        StatusBar bar = new StatusBar(intent -> {
        });
        SwingUtilities.invokeAndWait(() -> bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Week", 80, "")), 1L, "")));
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.WARN);
        SwingUtilities.invokeAndWait(() -> bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 95, "")), 1L, "")));
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.DANGER);
    }
}
