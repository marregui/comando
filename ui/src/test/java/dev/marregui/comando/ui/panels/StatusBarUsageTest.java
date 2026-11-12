package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Gauge;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [StatusBar]'s plan-usage labels: one per CLI, each window's percentage, coloured by how near its limit.
/// - the text starts with the plan's name, lists each window and says when the session window resets, and the week's
///   on which weekday, its year the one nearest the read
/// - a read without a session window says so in the tooltip
/// - a failed read keeps the last good numbers, muted, and says why in the tooltip
/// - the second CLI's read has a label of its own after the first, and its failure leaves the first alone
/// - a CLI that cannot run never shows its label
/// - a window near its limit turns the label to attention, then danger
/// - under the console style each window is a gauge, coloured and muted as the label is, with the label's tooltip
/// - a theme change swaps the label and the gauges at once, keeping the last read, while the bar is in a window
class StatusBarUsageTest {

    /// Noon on 2026-10-14 where the test runs: the reads' time, so the resets' dates fall in 2026.
    private static final long OCT_14_2026 = at(2026, 10, 14);
    private static final PlanUsage READ = new PlanUsage(List.of(
            new PlanUsage.Window("Session", 5, "Oct 14 at 7:19pm (Europe/Madrid)"),
            new PlanUsage.Window("Week", 43, "Oct 15 at 1:59pm (Europe/Madrid)"),
            new PlanUsage.Window("Fable", 17, "Oct 15 at 1:59pm (Europe/Madrid)")), OCT_14_2026, "");
    private static final String READ_TEXT = "Claude Session 5% (resets 7:19pm) · Week 43% (resets "
            + weekday(2026, 10, 15) + " 1:59pm) · Fable 17%";

    /// [StatusBar#usageText] joins the windows with the session's reset time; [StatusBar#resetTime] takes the clock
    /// time out of a dated reset and returns empty for a relative one.
    @Test
    void theBarShowsEachWindowAndWhenTheSessionResets() {
        assertThat(StatusBar.usageText(READ)).isEqualTo(READ_TEXT);
        assertThat(StatusBar.resetTime("Oct 15 at 1:59pm (Europe/Madrid)")).isEqualTo("1:59pm");
        assertThat(StatusBar.resetTime("in 3 hours")).isEmpty();
    }

    /// Claude's week window says the weekday it resets on and the time; the session window keeps the time alone and
    /// the model's week says nothing. 2026-10-15 is a Thursday by `java.time`.
    @Test
    void claudesWeekSaysTheWeekdayItResetsOn() {
        assertThat(weekday(2026, 10, 15)).isEqualTo("Thu");
        assertThat(StatusBar.usageText(READ))
                .isEqualTo("Claude Session 5% (resets 7:19pm) · Week 43% (resets Thu 1:59pm) · Fable 17%");
    }

    /// Codex's week reset, worded "Oct 15 at 13:00", says the weekday and its 24-hour time.
    @Test
    void codexsWeekSaysTheWeekdayItResetsOn() {
        final PlanUsage codex = new PlanUsage(PlanUsage.CODEX,
                List.of(new PlanUsage.Window("Session", 4, "Oct 14 at 12:06"),
                        new PlanUsage.Window("Week", 3, "Oct 15 at 13:00")), OCT_14_2026, "");
        assertThat(StatusBar.usageText(codex)).isEqualTo("Codex Session 4% (resets 12:06) · Week 3% (resets "
                + weekday(2026, 10, 15) + " 13:00)");
    }

    /// The reset text has no year: a December read of "Jan 2" is next year's Jan 2, a January read of "Dec 30" last
    /// year's, each weekday by `java.time` and different from the same date in the read's own year.
    @Test
    void aResetAcrossTheNewYearTakesTheNearestYear() {
        assertThat(StatusBar.weekReset("Jan 2 at 1pm (Europe/Madrid)", at(2026, 12, 20)))
                .isEqualTo(weekday(2027, 1, 2) + " 1pm")
                .isNotEqualTo(weekday(2026, 1, 2) + " 1pm");
        assertThat(StatusBar.weekReset("Dec 30 at 13:00", at(2026, 1, 3)))
                .isEqualTo(weekday(2025, 12, 30) + " 13:00")
                .isNotEqualTo(weekday(2026, 12, 30) + " 13:00");
    }

    /// A week reset without a date it can read shows the time alone; one without a time, or none at all, shows nothing.
    @Test
    void aWeekResetWithoutADateShowsTheTimeAlone() {
        assertThat(StatusBar.weekReset("soon at 1:59pm (Europe/Madrid)", OCT_14_2026)).isEqualTo("1:59pm");
        assertThat(StatusBar.weekReset("in 3 hours", OCT_14_2026)).isEmpty();
        assertThat(StatusBar.usageText(
                new PlanUsage(List.of(new PlanUsage.Window("Week", 43, "soon at 1:59pm")), OCT_14_2026, "")))
                .isEqualTo("Claude Week 43% (resets 1:59pm)");
        assertThat(StatusBar.usageText(new PlanUsage(List.of(new PlanUsage.Window("Week", 43, "")), OCT_14_2026, "")))
                .isEqualTo("Claude Week 43%");
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
            bar.planUsage(new PlanUsage(weeks, OCT_14_2026, ""));
            assertThat(bar.usageLabel().getText())
                    .isEqualTo("Claude Week 25% (resets " + weekday(2026, 11, 12) + " 1pm) · Fable 0%");
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
        assertThat(label.getText()).isEqualTo(READ_TEXT);
        assertThat(label.getToolTipText()).contains("Fable: 17% used, resets Oct 15 at 1:59pm (Europe/Madrid)").doesNotContain("failed");

        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed("<b>timeout</b>", 3L)));
        assertThat(label.getText()).as("the last good read stays")
                .isEqualTo(READ_TEXT);
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
                new PlanUsage.Window("Week", 92, "Nov 2 at 14:15")), OCT_14_2026, "");
        final String codexText = "Codex Session 4% (resets 12:06) · Week 92% (resets " + weekday(2026, 11, 2)
                + " 14:15)";
        SwingUtilities.invokeAndWait(() -> bar.planUsage(READ));
        SwingUtilities.invokeAndWait(() -> bar.planUsage(codex));
        assertThat(StatusBar.usageText(codex)).isEqualTo(codexText);
        assertThat(bar.codexUsageLabel().isVisible()).isTrue();
        assertThat(bar.usageLabel().getAccessibleContext().getAccessibleName()).isEqualTo("Claude plan usage");
        assertThat(bar.codexUsageLabel().getAccessibleContext().getAccessibleName()).isEqualTo("Codex plan usage");
        assertThat(bar.codexUsageLabel().getText()).isEqualTo(codexText);
        assertThat(bar.codexUsageLabel().getForeground()).isEqualTo(Theme.DANGER);
        assertThat(bar.codexUsageLabel().getToolTipText()).contains("Week: 92% used, resets Nov 2 at 14:15").contains("from codex app-server");
        assertThat(bar.usageLabel().getText()).as("Claude's label keeps its own read")
                .isEqualTo(READ_TEXT);
        assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.MUTED);
        final int[] order = {bar.usageLabel().getParent().getComponentZOrder(bar.usageLabel()),
                bar.usageLabel().getParent().getComponentZOrder(bar.codexUsageLabel())};
        assertThat(order[0]).as("Claude's first, then Codex's").isLessThan(order[1]);

        SwingUtilities.invokeAndWait(() -> bar.planUsage(PlanUsage.failed(PlanUsage.CODEX, "timeout", 2L)));
        assertThat(bar.codexUsageLabel().getText()).as("the last good Codex read stays").isEqualTo(codexText);
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

    /// Under the console style a read shows a gauge per window, captioned with the plan's initial and the window's name,
    /// filled to its percent and carrying the label's tooltip; the label hides and keeps its text.
    @Test
    @ExtendWith(OnEdt.class)
    void underTheConsoleStyleEachWindowIsAGauge() {
        ConsoleChecks.inTheme(NamedTheme.CONSOLE_AMBER, () -> {
            final StatusBar bar = new StatusBar(intent -> {
            });
            bar.planUsage(READ);
            final List<Gauge> gauges = showing(bar.component());
            assertThat(gauges).extracting(Gauge::caption).containsExactly("CL SESSION", "CL WEEK", "CL FABLE");
            assertThat(gauges).extracting(Gauge::percent).containsExactly(5, 43, 17);
            assertThat(gauges).extracting(Gauge::fill).containsOnly(Theme.MUTED);
            assertThat(gauges).extracting(Gauge::getToolTipText).containsOnly(bar.usageLabel().getToolTipText());
            assertThat(bar.usageLabel().isVisible()).as("the gauges stand for it").isFalse();
            assertThat(bar.usageLabel().getText()).isEqualTo(READ_TEXT);
        });
    }

    /// Under the console style a window at 40% fills muted, one at 80% in [Theme#ATTENTION] and one at 96% in
    /// [Theme#DANGER], each gauge by its own window.
    @Test
    @ExtendWith(OnEdt.class)
    void aGaugeNearItsLimitTurnsToAWarningThenADanger() {
        ConsoleChecks.inTheme(NamedTheme.CONSOLE_AMBER, () -> {
            final StatusBar bar = new StatusBar(intent -> {
            });
            bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 40, ""),
                    new PlanUsage.Window("Week", 96, "")), 1L, ""));
            assertThat(showing(bar.component())).extracting(Gauge::fill).containsExactly(Theme.MUTED, Theme.DANGER);
            bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 80, ""),
                    new PlanUsage.Window("Week", 96, "")), 1L, ""));
            assertThat(showing(bar.component())).extracting(Gauge::fill).containsExactly(Theme.ATTENTION, Theme.DANGER);
        });
    }

    /// Under the console style a failed read after a good one keeps the gauges at their percents, all muted, and their
    /// tooltip says why; the second plan's gauges follow its own label.
    @Test
    @ExtendWith(OnEdt.class)
    void aFailedReadKeepsTheGaugesMutedAndSaysWhy() {
        ConsoleChecks.inTheme(NamedTheme.CONSOLE_AMBER, () -> {
            final StatusBar bar = new StatusBar(intent -> {
            });
            bar.planUsage(new PlanUsage(PlanUsage.CODEX, List.of(new PlanUsage.Window("Week", 92, "")), 1L, ""));
            assertThat(showing(bar.component())).extracting(Gauge::caption, Gauge::fill)
                    .containsExactly(tuple("CO WEEK", Theme.DANGER));
            bar.planUsage(PlanUsage.failed(PlanUsage.CODEX, "timeout", 2L));
            final List<Gauge> gauges = showing(bar.component());
            assertThat(gauges).extracting(Gauge::percent).containsExactly(92);
            assertThat(gauges).extracting(Gauge::fill).containsExactly(Theme.MUTED);
            assertThat(gauges.getFirst().getToolTipText()).contains("failed: timeout")
                    .isEqualTo(bar.codexUsageLabel().getToolTipText());
        });
    }

    /// Under a plain theme a read shows the label as before and no gauge.
    @Test
    @ExtendWith(OnEdt.class)
    void underAPlainThemeNoGaugeShows() {
        ConsoleChecks.inTheme(NamedTheme.GRAPHITE_GOLD, () -> {
            final StatusBar bar = new StatusBar(intent -> {
            });
            bar.planUsage(READ);
            assertThat(showing(bar.component())).isEmpty();
            assertThat(bar.usageLabel().isVisible()).isTrue();
            assertThat(bar.usageLabel().getText()).isEqualTo(READ_TEXT);
            assertThat(bar.usageLabel().getForeground()).isEqualTo(Theme.MUTED);
        });
    }

    /// While the bar is in a window, choosing the console style turns the last read into gauges and choosing a plain
    /// theme turns it back into the label, with no new read; out of the window it no longer follows the theme.
    @Test
    @ExtendWith(OnEdt.class)
    void aThemeChangeSwapsTheLabelAndTheGaugesKeepingTheRead() {
        ConsoleChecks.inTheme(NamedTheme.GRAPHITE_GOLD, () -> {
            final StatusBar bar = new StatusBar(intent -> {
            });
            final JComponent root = bar.component();
            root.addNotify();
            try {
                bar.planUsage(READ);
                Theme.choose(NamedTheme.CONSOLE_AMBER.ground, NamedTheme.CONSOLE_AMBER.tint);
                assertThat(showing(root)).extracting(Gauge::percent).containsExactly(5, 43, 17);
                assertThat(bar.usageLabel().isVisible()).isFalse();
                Theme.choose(NamedTheme.GRAPHITE_GOLD.ground, NamedTheme.GRAPHITE_GOLD.tint);
                assertThat(showing(root)).isEmpty();
                assertThat(bar.usageLabel().isVisible()).isTrue();
            } finally {
                root.removeNotify();
            }
            Theme.choose(NamedTheme.CONSOLE_AMBER.ground, NamedTheme.CONSOLE_AMBER.tint);
            assertThat(bar.usageLabel().isVisible()).as("out of its window it stopped listening").isTrue();
        });
    }


    /// Noon on the given day in the system zone, as epoch milliseconds: the zone [StatusBar] reads a reset's year in.
    private static long at(final int year, final int month, final int day) {
        return LocalDateTime.of(year, month, day, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /// The English short weekday of the given date by `java.time`: "Thu".
    private static String weekday(final int year, final int month, final int day) {
        return LocalDate.of(year, month, day).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /// The gauges under `root` that show: each, and every container up to `root`, visible.
    private static List<Gauge> showing(final JComponent root) {
        final List<Gauge> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(final Component c, final List<Gauge> out) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof Gauge g) {
            out.add(g);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, out);
            }
        }
    }
}
