package dev.marregui.comando.ui.kit.async;

import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/// [SharedClock] ticks many [ShownTimer]s on one Swing timer.
/// - One beat ticks every running timer, in the order they started, in the same event.
/// - The clock runs while any of its timers runs, and stops when the last one stops or its owner hides.
/// - A timer stopped by a tick earlier in the same beat is not ticked; one started by a tick is ticked from the next.
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
}
