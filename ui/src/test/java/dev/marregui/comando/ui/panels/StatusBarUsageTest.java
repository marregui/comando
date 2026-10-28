package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PlanUsage;
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
    void codexHasALabelOfItsOwnAfterClaudes() throws Exception {
        StatusBar bar = new StatusBar(intent -> {
        });
        PlanUsage codex = new PlanUsage(PlanUsage.CODEX, List.of(new PlanUsage.Window("Session", 4, "Oct 27 at 12:06"),
                new PlanUsage.Window("Week", 92, "Nov 2 at 14:15")), 1L, "");
        SwingUtilities.invokeAndWait(() -> bar.planUsage(READ));
        SwingUtilities.invokeAndWait(() -> bar.planUsage(codex));
        assertThat(StatusBar.usageText(codex)).isEqualTo("Codex Session 4% (resets 12:06) · Week 92%");
        assertThat(bar.codexUsageLabel().isVisible()).isTrue();
        assertThat(bar.codexUsageLabel().getText()).isEqualTo("Codex Session 4% (resets 12:06) · Week 92%");
        assertThat(bar.codexUsageLabel().getForeground()).isEqualTo(Theme.DANGER);
        assertThat(bar.codexUsageLabel().getToolTipText()).contains("Week: 92% used, resets Nov 2 at 14:15").contains("from codex app-server");
        assertThat(bar.usageLabel().getText()).as("Claude's label keeps its own read").isEqualTo("Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.MUTED);
        int[] order = {bar.usageLabel().getParent().getComponentZOrder(bar.usageLabel()),
                bar.usageLabel().getParent().getComponentZOrder(bar.codexUsageLabel())};
        assertThat(order[0]).as("Claude's first, then Codex's").isLessThan(order[1]);

        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed(PlanUsage.CODEX, "timeout", 2L)));
        assertThat(bar.codexUsageLabel().getText()).as("the last good Codex read stays").isEqualTo("Codex Session 4% (resets 12:06) · Week 92%");
        assertThat(bar.codexUsageLabel().getForeground()).isEqualTo(Theme.MUTED);
        assertThat(bar.usageLabel().getToolTipText()).as("a Codex failure is not Claude's").doesNotContain("failed");
    }

    @Test
    void withoutCodexItsLabelNeverShows() throws Exception {
        StatusBar bar = new StatusBar(intent -> {
        });
        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed(PlanUsage.CODEX, "cannot run 'codex'", 2L)));
        assertThat(bar.codexUsageLabel().isVisible()).isFalse();
    }

    @Test
    void aReadWithNothingToShowLeavesTheKitShown() throws Exception {
        StatusBar bar = new StatusBar(intent -> {
        });
        SwingUtilities.invokeAndWait(() -> bar.kit("kit test-kit", "", false));
        int listeners = bar.kitLabel().getMouseListeners().length;
        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed("not logged in", 2L)));
        assertThat(bar.kitLabel().isVisible()).isTrue();
        assertThat(bar.kitLabel().getMouseListeners()).hasSize(listeners);
    }

    @Test
    void aWindowNearItsLimitTurnsTheLabelToAWarningThenADanger() throws Exception {
        StatusBar bar = new StatusBar(intent -> {
        });
        SwingUtilities.invokeAndWait(() -> bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Week", 80, "")), 1L, "")));
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.ATTENTION);
        SwingUtilities.invokeAndWait(() -> bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 95, "")), 1L, "")));
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.DANGER);
    }
}
