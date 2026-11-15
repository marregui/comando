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
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionEta;
import dev.marregui.comando.core.state.SessionEta.Run;
import dev.marregui.comando.core.state.SessionEta.Run.Source;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [SessionBar]'s ETA, always on: the run's line and its card.
/// - running: when it started, for how long, when it is due (`~` for comando's default), how far the session says it is
/// - late: how late, rounded up, in attention colour; an ended run: how it met its deadline, muted
/// - no run yet says so; the checkpoint turns the line purple and ends it with how to carry on
/// - the card says the run, its deadline's source, its times, the session's report and what the session does now
class SessionBarEtaTest {

    /// 2026-11-15 11:00:00 UTC.
    private static final long NOW_MILLIS = 1_794_740_400_000L;
    private static final long MINUTE_MILLIS = 60_000L;
    /// 10:48:00 UTC, twelve minutes before now.
    private static final long STARTED_MILLIS = NOW_MILLIS - 12 * MINUTE_MILLIS;

    /// A timeboxed run on time shows its start, its elapsed time, its deadline and its four-minute-old report, muted.
    @Test
    void aRunningRunOnTimeShowsItsStartElapsedDeadlineAndReport() {
        final SessionEta e = eta(new Run(STARTED_MILLIS, NOW_MILLIS + 30 * MINUTE_MILLIS, Source.BOX, 60,
                NOW_MILLIS - 4 * MINUTE_MILLIS, 0L));
        assertThat(SessionBar.hudText(e, NOW_MILLIS))
                .isEqualTo("10:48 UTC ▸ 12 min 00 s ▸ due 11:30 UTC ▸ 60% (4 min ago)");
        assertThat(SessionBar.lineColour(open(e), NOW_MILLIS)).isEqualTo(Theme.MUTED);
    }

    /// comando's default deadline is marked `~`; a session's estimate is not.
    @Test
    void aDefaultDeadlineIsMarkedApproximate() {
        final Run run = new Run(STARTED_MILLIS, NOW_MILLIS + 5 * MINUTE_MILLIS, Source.DEFAULT, -1, 0L, 0L);
        assertThat(SessionBar.hudText(eta(run), NOW_MILLIS))
                .isEqualTo("10:48 UTC ▸ 12 min 00 s ▸ due ~11:05 UTC ▸ –%");
        assertThat(SessionBar.hudText(eta(new Run(STARTED_MILLIS, NOW_MILLIS + 5 * MINUTE_MILLIS, Source.ESTIMATE, -1,
                0L, 0L)), NOW_MILLIS)).isEqualTo("10:48 UTC ▸ 12 min 00 s ▸ due 11:05 UTC ▸ –%");
    }

    /// Five minutes and a millisecond past its deadline the line says "+6 min", in attention colour; at the deadline it
    /// is not late yet.
    @Test
    void aLateRunSaysHowLateRoundedUpInAttentionColour() {
        final long deadlineMillis = NOW_MILLIS - 5 * MINUTE_MILLIS - 1L;
        final SessionEta e = eta(new Run(STARTED_MILLIS, deadlineMillis, Source.ESTIMATE, -1, 0L, 0L));
        assertThat(SessionBar.hudText(e, NOW_MILLIS))
                .isEqualTo("10:48 UTC ▸ 12 min 00 s ▸ due 10:54 UTC (+6 min) ▸ –%");
        assertThat(SessionBar.lineColour(open(e), NOW_MILLIS)).isEqualTo(Theme.ATTENTION);
        assertThat(SessionBar.hudText(e, deadlineMillis)).doesNotContain("(+");
        assertThat(SessionBar.lineColour(open(e), deadlineMillis)).isEqualTo(Theme.MUTED);
    }

    /// The percent reads "–%" before any report, bare while the report is under a minute old, with its age from a
    /// minute on.
    @Test
    void thePercentShowsItsAgeOnceAMinuteOld() {
        final long deadlineMillis = NOW_MILLIS + 30 * MINUTE_MILLIS;
        final String head = "10:48 UTC ▸ 12 min 00 s ▸ due 11:30 UTC ▸ ";
        assertThat(SessionBar.hudText(eta(new Run(STARTED_MILLIS, deadlineMillis, Source.BOX, -1, 0L, 0L)), NOW_MILLIS))
                .isEqualTo(head + "–%");
        assertThat(SessionBar.hudText(eta(new Run(STARTED_MILLIS, deadlineMillis, Source.BOX, 60,
                NOW_MILLIS - MINUTE_MILLIS + 1L, 0L)), NOW_MILLIS)).isEqualTo(head + "60%");
        assertThat(SessionBar.hudText(eta(new Run(STARTED_MILLIS, deadlineMillis, Source.BOX, 60,
                NOW_MILLIS - MINUTE_MILLIS, 0L)), NOW_MILLIS)).isEqualTo(head + "60% (1 min ago)");
    }

    /// An ended run says how long it took and how it met its deadline, in whole minutes; within a minute it is on
    /// time; a run ended past its deadline stays muted.
    @Test
    void anEndedRunSaysHowItMetItsDeadline() {
        final long startedMillis = NOW_MILLIS - 60 * MINUTE_MILLIS;
        final long deadlineMillis = startedMillis + 28 * MINUTE_MILLIS;
        assertThat(SessionBar.hudText(eta(new Run(startedMillis, deadlineMillis, Source.BOX, 90, startedMillis,
                startedMillis + 27 * MINUTE_MILLIS)), NOW_MILLIS))
                .isEqualTo("10:00 UTC ▸ 27 min 00 s ▸ due 10:28 UTC ▸ done, 1 min early");
        final SessionEta late = eta(new Run(startedMillis, deadlineMillis, Source.DEFAULT, -1, 0L,
                deadlineMillis + 3 * MINUTE_MILLIS + 30_000L));
        assertThat(SessionBar.hudText(late, NOW_MILLIS))
                .isEqualTo("10:00 UTC ▸ 31 min 30 s ▸ due ~10:28 UTC ▸ done, 3 min late");
        assertThat(SessionBar.lineColour(open(late), NOW_MILLIS)).isEqualTo(Theme.MUTED);
        assertThat(SessionBar.hudText(eta(new Run(startedMillis, deadlineMillis, Source.ESTIMATE, -1, 0L,
                deadlineMillis + MINUTE_MILLIS - 1L)), NOW_MILLIS)).endsWith(" ▸ done, on time");
        assertThat(SessionBar.hudText(eta(new Run(startedMillis, deadlineMillis, Source.ESTIMATE, -1, 0L,
                deadlineMillis - MINUTE_MILLIS + 1L)), NOW_MILLIS)).endsWith(" ▸ done, on time");
    }

    /// Before its first run a session's line says so, muted.
    @Test
    void noRunYetSaysSo() {
        assertThat(SessionBar.hudText(SessionEta.NONE, NOW_MILLIS)).isEqualTo("no run yet");
        assertThat(SessionBar.lineColour(open(SessionEta.NONE), NOW_MILLIS)).isEqualTo(Theme.MUTED);
    }

    /// While the checkpoint runs the line is purple, even late, and ends with how to carry on; once it ends the hint
    /// goes.
    @Test
    void theCheckpointTurnsTheLinePurpleAndSaysHowToCarryOn() {
        final SessionEta late = eta(new Run(STARTED_MILLIS, NOW_MILLIS - MINUTE_MILLIS, Source.ESTIMATE, -1, 0L, 0L));
        final SessionState locked = busy(late).withCheckpointTurns(1);
        assertThat(SessionBar.lineText(locked, NOW_MILLIS))
                .isEqualTo("10:48 UTC ▸ 12 min 00 s ▸ due 10:59 UTC (+1 min) ▸ –% ▸ checkpoint · "
                        + "Stop to carry on");
        assertThat(SessionBar.lineColour(locked, NOW_MILLIS)).isEqualTo(Theme.THINKING);
        assertThat(SessionBar.lineText(busy(late), NOW_MILLIS)).doesNotContain(SessionBar.LOCKED_HINT);
        assertThat(SessionBar.lineColour(busy(late), NOW_MILLIS)).isEqualTo(Theme.ATTENTION);
    }

    /// A running task's card names the run, the timebox its deadline came from, its times to the second, the
    /// session's report, the step, the goal, the timebox, what the turn does and what is queued, then the time now,
    /// in that order.
    @Test
    void aRunningTasksCardSaysEveryFactInOrder() {
        final SessionEta e = new SessionEta("build", NOW_MILLIS + 8 * MINUTE_MILLIS, "shipped",
                NOW_MILLIS + 20 * MINUTE_MILLIS, 30, STARTED_MILLIS, NOW_MILLIS - MINUTE_MILLIS)
                .withRun(new Run(STARTED_MILLIS, STARTED_MILLIS + 30 * MINUTE_MILLIS, Source.BOX, 60,
                        NOW_MILLIS - 4 * MINUTE_MILLIS - 2_000L, 0L));
        final SessionState ss = SessionState.closed(SessionSpec.fresh("work", "/w")).withStatus(Status.OPEN, "")
                .withTurns(3, NOW_MILLIS - 90_000L).withActivity("Bash", NOW_MILLIS).withEta(e);
        final SessionBar.CardText card = SessionBar.etaCard(ss, NOW_MILLIS);
        assertThat(card.head().title()).isEqualTo("ETA · work");
        assertThat(card.head().facts()).extracting(CardFacts.Fact::label, CardFacts.Fact::value).containsExactly(
                tuple("Run", "task"),
                tuple("Deadline from", "timebox 30 min"),
                tuple("Started", "10:48:00 UTC"),
                tuple("Running for", "12 min 00 s"),
                tuple("Due", "11:18:00 UTC, 18 min left"),
                tuple("Progress", "60%, said 10:55:58 UTC, 4 min 02 s ago"),
                tuple("Step", "build · due 11:08:00 UTC, 8 min left"),
                tuple("Goal", "shipped · due 11:20:00 UTC, 20 min left"),
                tuple("Timebox", "30 min, given at 10:48:00 UTC · ends 11:18:00 UTC, 18 min left"),
                tuple("Doing", "Bash"),
                tuple("Turn started", "10:58:30 UTC, 1 min 30 s ago"),
                tuple("Queued", "2 prompts"),
                tuple("Now", "11:00:00 UTC"));
        assertThat(card.text()).startsWith("The run is the task the session declared");
    }

    /// The card's deadline source is the run's own: a timebox the estimate has since cleared still reads as the run's
    /// 30 minutes, and a goal's estimate names its span.
    @Test
    void theDeadlineSourceIsTheRunsNotTheCurrentEstimates() {
        final SessionEta cleared = SessionEta.NONE.withRun(new Run(STARTED_MILLIS, STARTED_MILLIS + 30 * MINUTE_MILLIS,
                Source.BOX, -1, 0L, NOW_MILLIS));
        final SessionEta goal = SessionEta.NONE.withRun(new Run(STARTED_MILLIS, STARTED_MILLIS + 20 * MINUTE_MILLIS,
                Source.ESTIMATE, -1, 0L, 0L));
        assertThat(SessionBar.etaCard(open(cleared), NOW_MILLIS).head().facts()).extracting(CardFacts.Fact::label,
                CardFacts.Fact::value).contains(tuple("Deadline from", "timebox 30 min"));
        assertThat(SessionBar.etaCard(open(goal), NOW_MILLIS).head().facts()).extracting(CardFacts.Fact::label,
                CardFacts.Fact::value).contains(tuple("Deadline from", "session estimate, 20 min"));
    }

    /// An ended turn's card says it took, how it met its default deadline, the median it came from and that no report
    /// came; a session waiting on background work says on what and since when.
    @Test
    void anEndedTurnsCardSaysHowItEndedAndWhatTheSessionWaitsOn() {
        final long startedMillis = NOW_MILLIS - 40 * MINUTE_MILLIS;
        final SessionEta e = SessionEta.NONE.withTurn(2 * MINUTE_MILLIS).withTurn(4 * MINUTE_MILLIS)
                .withTurn(6 * MINUTE_MILLIS)
                .withRun(new Run(startedMillis, startedMillis + 4 * MINUTE_MILLIS, Source.DEFAULT, -1, 0L,
                        startedMillis + 7 * MINUTE_MILLIS));
        final SessionState ss = SessionState.closed(SessionSpec.fresh("work", "/w")).withStatus(Status.OPEN, "")
                .withTasks(List.of(new BackgroundTask("a1", "local_agent", "review agent", 0L)), NOW_MILLIS - 5_000L)
                .withEta(e);
        assertThat(SessionBar.etaCard(ss, NOW_MILLIS).head().facts())
                .extracting(CardFacts.Fact::label, CardFacts.Fact::value)
                .containsExactly(
                        tuple("Run", "turn"),
                        tuple("Deadline from", "default 4 min: the median of the session's last turns, 5 min before any"),
                        tuple("Started", "10:20:00 UTC"),
                        tuple("Took", "7 min 00 s"),
                        tuple("Due", "10:24:00 UTC, done, 3 min late"),
                        tuple("Progress", "not reported"),
                        tuple("Waiting on", "review agent"),
                        tuple("Since", "10:59:55 UTC, 5 s ago"),
                        tuple("Now", "11:00:00 UTC"));
    }

    /// A turn with no turn behind it names the five-minute default; before any run the card says none yet; while the
    /// checkpoint runs it says how to carry on.
    @Test
    void theCardNamesTheFirstDefaultNoRunAndTheCheckpoint() {
        final SessionEta first = eta(new Run(STARTED_MILLIS, STARTED_MILLIS + 5 * MINUTE_MILLIS, Source.DEFAULT, -1,
                0L, 0L));
        assertThat(SessionBar.etaCard(open(first), NOW_MILLIS).head().facts()).extracting(CardFacts.Fact::label,
                CardFacts.Fact::value).contains(tuple("Deadline from", "default 5 min: the median of the session's last turns, 5 min before any"),
                tuple("Due", "10:53:00 UTC, 7 min late"));
        assertThat(SessionBar.etaCard(open(SessionEta.NONE), NOW_MILLIS).head().facts())
                .extracting(CardFacts.Fact::label, CardFacts.Fact::value)
                .containsExactly(tuple("Run", "none yet"), tuple("Now", "11:00:00 UTC"));
        assertThat(SessionBar.etaCard(busy(first).withCheckpointTurns(1), NOW_MILLIS).head().facts())
                .extracting(CardFacts.Fact::label, CardFacts.Fact::value)
                .contains(tuple("Doing", "checkpoint · working"), tuple("To carry on", SessionBar.LOCKED_HINT));
    }

    /// A stopped session with no run shows "no run yet", muted; the label stays shown as the session starts.
    @Test
    void theBarShowsTheLineForEverySession() throws Exception {
        final PanelId id = PanelId.random();
        final SessionState stopped = SessionState.closed(SessionSpec.fresh("work", "/w"));
        final boolean[] shown = new boolean[2];
        final String[] text = new String[1];
        final JLabel[] eta = new JLabel[1];
        SwingUtilities.invokeAndWait(() -> {
            final SessionBar bar = new SessionBar(_ -> { });
            final AppState s = AppState.initial(List.of(new PanelState(id, "work", "", "work")), List.of(), List.of(stopped));
            bar.render(s);
            eta[0] = label(bar);
            shown[0] = eta[0].isVisible();
            text[0] = eta[0].getText();
            bar.render(s.withSession("work", x -> x.withStatus(Status.OPEN, "")));
            shown[1] = eta[0].isVisible();
        });
        assertThat(shown).containsExactly(true, true);
        assertThat(text[0]).isEqualTo("no run yet");
        assertThat(eta[0].getForeground()).isEqualTo(Theme.MUTED);
    }

    /// The shell's one-second tick draws a running run's line again, so its elapsed time moves.
    @Test
    void theTickMovesARunningLine() throws Exception {
        final PanelId id = PanelId.random();
        final long startedMillis = System.currentTimeMillis() - 65_000L;
        final SessionState running = SessionState.closed(SessionSpec.fresh("work", "/w")).withStatus(Status.OPEN, "")
                .withEta(eta(new Run(startedMillis, startedMillis + 10 * MINUTE_MILLIS, Source.DEFAULT, -1, 0L, 0L)));
        final String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            final SessionBar bar = new SessionBar(_ -> { });
            bar.render(AppState.initial(List.of(new PanelState(id, "work", "", "work")), List.of(), List.of(running)));
            final JLabel eta = label(bar);
            eta.setText("stale");
            bar.tick();
            text[0] = eta.getText();
        });
        assertThat(text[0]).matches(".+ UTC ▸ 1 min 0\\d s ▸ due ~.+ UTC ▸ –%");
    }

    /// The label asks for at most [SessionBar#ETA_COLUMNS] digit widths: a checkpoint's line after a late run ends in
    /// an ellipsis rather than take the session name's width.
    @Test
    void theLineKeepsItsWidthCap() throws Exception {
        final PanelId id = PanelId.random();
        final long startedMillis = System.currentTimeMillis() - 72 * MINUTE_MILLIS;
        final SessionState locked = busy(eta(new Run(startedMillis, startedMillis + 30 * MINUTE_MILLIS,
                Source.DEFAULT, 60, startedMillis + 50 * MINUTE_MILLIS, 0L))).withCheckpointTurns(1);
        final int[] widths = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            final SessionBar bar = new SessionBar(_ -> { });
            bar.render(AppState.initial(List.of(new PanelState(id, "work", "", "work")), List.of(), List.of(locked)));
            final JLabel eta = label(bar);
            eta.setText(eta.getText() + " and a long tail of words that the bar has no room for at all");
            widths[0] = eta.getPreferredSize().width;
            widths[1] = eta.getFontMetrics(eta.getFont()).charWidth('0') * SessionBar.ETA_COLUMNS + eta.getInsets().left
                    + eta.getInsets().right;
        });
        assertThat(widths[0]).isEqualTo(widths[1]);
    }

    private static SessionEta eta(final Run run) {
        return SessionEta.NONE.withRun(run);
    }

    private static SessionState open(final SessionEta e) {
        return SessionState.closed(SessionSpec.fresh("work", "/w")).withStatus(Status.OPEN, "").withEta(e);
    }

    private static SessionState busy(final SessionEta e) {
        return SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withTurns(1, 1L).withEta(e);
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
