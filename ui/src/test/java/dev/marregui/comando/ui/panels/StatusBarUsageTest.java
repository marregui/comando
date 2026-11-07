package dev.marregui.comando.ui.panels;

import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [StatusBar]'s plan-usage labels: one per CLI, each window's percentage, coloured by how near its limit.
/// - the text starts with the plan's name, lists each window and says when the session window resets
/// - a read without a session window says so in the tooltip
/// - a failed read keeps the last good numbers, muted, and says why in the tooltip
/// - the second CLI's read has a label of its own after the first, and its failure leaves the first alone
/// - a CLI that cannot run never shows its label
/// - a window near its limit turns the label to attention, then danger
class StatusBarUsageTest {

    private static final PlanUsage READ = new PlanUsage(List.of(
            new PlanUsage.Window("Session", 5, "Oct 14 at 7:19pm (Europe/Madrid)"),
            new PlanUsage.Window("Week", 43, "Oct 15 at 1:59pm (Europe/Madrid)"),
            new PlanUsage.Window("Fable", 17, "Oct 15 at 1:59pm (Europe/Madrid)")), 1L, "");

    /// [StatusBar#usageText] joins the windows with the session's reset time; [StatusBar#resetTime] takes the clock
    /// time out of a dated reset and returns empty for a relative one.
    @Test
    void theBarShowsEachWindowAndWhenTheSessionResets() {
        assertThat(StatusBar.usageText(READ)).isEqualTo("Claude Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(StatusBar.resetTime("Oct 15 at 1:59pm (Europe/Madrid)")).isEqualTo("1:59pm");
        assertThat(StatusBar.resetTime("in 3 hours")).isEmpty();
    }

    /// Claude Code lists no window the server reports no limit for: a read of the weeks alone shows them and the
    /// tooltip says the session is missing at the source, for either plan; a read with a session says nothing of the
    /// kind.
    @Test
    void aReadWithoutASessionWindowSaysSoInTheTooltip() throws Exception {
        final List<PlanUsage.Window> weeks = List.of(new PlanUsage.Window("Week", 25, "Nov 12 at 1pm (Europe/Madrid)"),
                new PlanUsage.Window("Fable", 0, "Nov 12 at 1pm (Europe/Madrid)"));
        SwingUtilities.invokeAndWait(() -> {
            final StatusBar bar = new StatusBar(intent -> {
            });
            bar.planUsage(new PlanUsage(weeks, 1L, ""));
            assertThat(bar.usageLabel().getText()).isEqualTo("Claude Week 25% · Fable 0%");
            assertThat(bar.usageLabel().getToolTipText()).contains("Claude Code's /usage reports no session window");

            bar.planUsage(new PlanUsage(PlanUsage.CODEX, weeks, 1L, ""));
            assertThat(bar.codexUsageLabel().getToolTipText()).contains("codex app-server reports no session window");

            bar.planUsage(READ);
            assertThat(bar.usageLabel().getToolTipText()).doesNotContain("no session window");
        });
    }

    /// A failed first read keeps the label hidden; after a good read, a failed one keeps its text, mutes it and adds
    /// the failure, HTML-escaped, to the tooltip.
    @Test
    void aFailedReadKeepsTheLastNumbersMutedAndSaysWhy() throws Exception {
        final StatusBar bar = new StatusBar(intent -> {
        });
        final JLabel label = bar.usageLabel();
        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed("not logged in", 2L)));
        assertThat(label.isVisible()).as("nothing to show yet").isFalse();

        SwingUtilities.invokeAndWait(() -> bar.planUsage(READ));
        assertThat(label.isVisible()).isTrue();
        assertThat(label.getText()).isEqualTo("Claude Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(label.getToolTipText()).contains("Fable: 17% used, resets Oct 15 at 1:59pm (Europe/Madrid)").doesNotContain("failed");

        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed("<b>timeout</b>", 3L)));
        assertThat(label.getText()).as("the last good read stays")
                .isEqualTo("Claude Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(label.getForeground()).isEqualTo(Theme.MUTED);
        assertThat(label.getToolTipText()).contains("failed: &lt;b&gt;timeout&lt;/b&gt;");
    }

    /// The second plan's read shows in its own label after the first, prefixed with its name, in danger at 92%, with
    /// its source in the tooltip; its later failure keeps its last text muted and adds no failure to the first label.
    @Test
    void codexHasALabelOfItsOwnAfterClaudes() throws Exception {
        final StatusBar bar = new StatusBar(intent -> {
        });
        final PlanUsage codex = new PlanUsage(PlanUsage.CODEX, List.of(new PlanUsage.Window("Session", 4, "Oct 27 at 12:06"),
                new PlanUsage.Window("Week", 92, "Nov 2 at 14:15")), 1L, "");
        SwingUtilities.invokeAndWait(() -> bar.planUsage(READ));
        SwingUtilities.invokeAndWait(() -> bar.planUsage(codex));
        assertThat(StatusBar.usageText(codex)).isEqualTo("Codex Session 4% (resets 12:06) · Week 92%");
        assertThat(bar.codexUsageLabel().isVisible()).isTrue();
        assertThat(bar.usageLabel().getAccessibleContext().getAccessibleName()).isEqualTo("Claude plan usage");
        assertThat(bar.codexUsageLabel().getAccessibleContext().getAccessibleName()).isEqualTo("Codex plan usage");
        assertThat(bar.codexUsageLabel().getText()).isEqualTo("Codex Session 4% (resets 12:06) · Week 92%");
        assertThat(bar.codexUsageLabel().getForeground()).isEqualTo(Theme.DANGER);
        assertThat(bar.codexUsageLabel().getToolTipText()).contains("Week: 92% used, resets Nov 2 at 14:15").contains("from codex app-server");
        assertThat(bar.usageLabel().getText()).as("Claude's label keeps its own read")
                .isEqualTo("Claude Session 5% (resets 7:19pm) · Week 43% · Fable 17%");
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.MUTED);
        final int[] order = {bar.usageLabel().getParent().getComponentZOrder(bar.usageLabel()),
                bar.usageLabel().getParent().getComponentZOrder(bar.codexUsageLabel())};
        assertThat(order[0]).as("Claude's first, then Codex's").isLessThan(order[1]);

        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed(PlanUsage.CODEX, "timeout", 2L)));
        assertThat(bar.codexUsageLabel().getText()).as("the last good Codex read stays").isEqualTo("Codex Session 4% (resets 12:06) · Week 92%");
        assertThat(bar.codexUsageLabel().getForeground()).isEqualTo(Theme.MUTED);
        assertThat(bar.usageLabel().getToolTipText()).as("a Codex failure is not Claude's").doesNotContain("failed");
    }

    /// A first read of the second plan that fails because its CLI cannot run leaves its label hidden.
    @Test
    void withoutCodexItsLabelNeverShows() throws Exception {
        final StatusBar bar = new StatusBar(intent -> {
        });
        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed(PlanUsage.CODEX, "cannot run 'codex'", 2L)));
        assertThat(bar.codexUsageLabel().isVisible()).isFalse();
    }

    /// A window at 80% turns the label to [Theme#ATTENTION], one at 95% to [Theme#DANGER].
    @Test
    void aWindowNearItsLimitTurnsTheLabelToAWarningThenADanger() throws Exception {
        final StatusBar bar = new StatusBar(intent -> {
        });
        SwingUtilities.invokeAndWait(() -> bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Week", 80, "")), 1L, "")));
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.ATTENTION);
        SwingUtilities.invokeAndWait(() -> bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 95, "")), 1L, "")));
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.DANGER);
    }
}
