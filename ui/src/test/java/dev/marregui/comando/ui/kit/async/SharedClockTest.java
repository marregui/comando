package dev.marregui.comando.ui.kit.async;

import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/// [SharedClock] ticks many [ShownTimer]s on one Swing timer.
/// - One beat ticks every running timer, in the order they started, in the same event.
/// - The clock runs while any of its timers runs, and stops when the last one stops or its owner hides.
/// - A timer stopped by a tick earlier in the same beat is not ticked; one started by a tick is ticked from the next.
/// - A timer ticks every beat while its window is in front, one beat in six while it is behind, never while minimised.
@ExtendWith(OnEdt.class)
class SharedClockTest {

    /// An owner whose showing state the test sets, announced as the toolkit does, by a `SHOWING_CHANGED` event.
    private static final class Owner extends JPanel {
        private boolean shown;

        @Override
        public boolean isShowing() {
            return shown;
        }

        void showing(final boolean on) {
            shown = on;
            dispatchEvent(new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, null, HierarchyEvent.SHOWING_CHANGED));
        }
    }

    private final SharedClock clock = new SharedClock(1_000);
    private final List<String> ticks = new ArrayList<>();
    private final Owner tabs = new Owner();
    private final Owner rows = new Owner();
    private final ShownTimer tabTimer = clock.timer(tabs, () -> ticks.add("tabs"));
    private final ShownTimer rowTimer = clock.timer(rows, () -> ticks.add("rows"));

    @AfterEach
    void stop() {
        tabTimer.stop();
        rowTimer.stop();
    }

    /// Two running timers tick in one beat, in the order they started; the clock runs while either runs.
    @Test
    void oneBeatTicksEveryRunningTimerInTheOrderTheyStarted() {
        rows.showing(true);
        tabs.showing(true);
        assertThat(clock.ticking()).as("nothing wanted").isFalse();
        rowTimer.want(true);
        tabTimer.want(true);
        assertThat(clock.ticking()).isTrue();

        clock.beat();
        assertThat(ticks).containsExactly("rows", "tabs");
        rowTimer.want(false);
        assertThat(clock.ticking()).as("one still runs").isTrue();
        clock.beat();
        assertThat(ticks).containsExactly("rows", "tabs", "tabs");
    }

    /// The clock stops when its last timer stops, whether unwanted or its owner hidden, and starts again with it.
    @Test
    void theClockStopsWithItsLastTimer() {
        tabs.showing(true);
        tabTimer.want(true);
        tabs.showing(false);
        assertThat(tabTimer.running()).isFalse();
        assertThat(clock.ticking()).as("its owner hidden").isFalse();
        tabs.showing(true);
        assertThat(clock.ticking()).as("shown again, still wanted").isTrue();
        tabTimer.stop();
        assertThat(clock.ticking()).as("stopped").isFalse();
        clock.beat();
        assertThat(ticks).as("a stopped timer is not ticked").isEmpty();
    }

    /// A tick that stops a later timer keeps it from ticking in that beat; a tick that starts one ticks it from the next.
    @Test
    void aTickChangesTheNextBeatNotThisOne() {
        final Owner strip = new Owner();
        final ShownTimer first = clock.timer(strip, () -> {
            ticks.add("first");
            rowTimer.stop();
            tabTimer.want(true);
        });
        try {
            strip.showing(true);
            rows.showing(true);
            tabs.showing(true);
            first.want(true);
            rowTimer.want(true);

            clock.beat();
            assertThat(ticks).as("rows stopped by first, tabs started by first").containsExactly("first");
            clock.beat();
            assertThat(ticks).containsExactly("first", "first", "tabs");
        } finally {
            first.stop();
        }
    }

    /// A window in front ticks every beat; one behind another application ticks on one beat in six, in the same
    /// event as those in front; a minimised one does not tick, and ticks every beat again once in front.
    @Test
    void howOftenATimerTicksFollowsHowItsWindowIsSeen() {
        final Map<Component, SharedClock.Seen> seen = new HashMap<>();
        final SharedClock watched = new SharedClock(1_000, seen::get);
        final Owner front = new Owner();
        final Owner behind = new Owner();
        final Owner minimised = new Owner();
        seen.put(front, SharedClock.Seen.FRONT);
        seen.put(behind, SharedClock.Seen.BEHIND);
        seen.put(minimised, SharedClock.Seen.MINIMISED);
        final List<ShownTimer> timers = List.of(
                watched.timer(front, () -> ticks.add("front")),
                watched.timer(behind, () -> ticks.add("behind")),
                watched.timer(minimised, () -> ticks.add("minimised")));
        try {
            for (final Owner o : List.of(front, behind, minimised)) {
                o.showing(true);
            }
            timers.forEach(t -> t.want(true));

            final int beats = 2 * SharedClock.BEHIND_EVERY_BEATS;
            for (int i = 0; i < beats; i++) {
                watched.beat();
            }
            assertThat(ticks.stream().filter("front"::equals).count()).isEqualTo(beats);
            assertThat(ticks.stream().filter("behind"::equals).count()).as("one beat in six").isEqualTo(2);
            assertThat(ticks).as("behind ticks with the first beat of each round")
                    .startsWith("front", "behind", "front")
                    .doesNotContain("minimised");
            assertThat(watched.ticking()).as("a minimised window's timer still runs, waiting to be seen").isTrue();

            ticks.clear();
            seen.put(minimised, SharedClock.Seen.FRONT);
            watched.beat();
            assertThat(ticks).as("in front again").contains("minimised");
        } finally {
            timers.forEach(ShownTimer::stop);
        }
    }

    /// An owner in no window, as every owner in a headless test, counts as in front.
    @Test
    void anOwnerInNoWindowIsInFront() {
        assertThat(SharedClock.seen(new JPanel())).isEqualTo(SharedClock.Seen.FRONT);
    }

    /// A minimised window is minimised whatever is in front; else it is in front while any comando window is active
    /// (a dialog of comando's included), and behind while another application is.
    @Test
    void aWindowIsSeenByMinimisedThenByWhetherComandoIsInFront() {
        assertThat(SharedClock.seen(true, true)).isEqualTo(SharedClock.Seen.MINIMISED);
        assertThat(SharedClock.seen(true, false)).isEqualTo(SharedClock.Seen.MINIMISED);
        assertThat(SharedClock.seen(false, true)).isEqualTo(SharedClock.Seen.FRONT);
        assertThat(SharedClock.seen(false, false)).isEqualTo(SharedClock.Seen.BEHIND);
    }
}
