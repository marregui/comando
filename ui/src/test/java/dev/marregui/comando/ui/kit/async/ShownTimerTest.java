package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JPanel;
import java.awt.event.HierarchyEvent;

import static org.assertj.core.api.Assertions.assertThat;

/// [ShownTimer] runs its repeating timer exactly while its owner wants ticks and is showing.
/// - Wanted but not showing, it never runs.
/// - Wanted and shown, it runs.
/// - Hidden, it stops; unwanted, it stops.
/// - Showing again resumes it only if it is still wanted.
@ExtendWith(OnEdt.class)
class ShownTimerTest {

    /// An owner whose showing state the test sets, announced as the toolkit does, by a `SHOWING_CHANGED` event.
    private static final class Owner extends JPanel {
        private boolean shown;

        @Override
        public boolean isShowing() {
            return shown;
        }

        void showing(boolean on) {
            shown = on;
            dispatchEvent(new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, null, HierarchyEvent.SHOWING_CHANGED));
        }
    }

    private final Owner owner = new Owner();
    private final ShownTimer timer = new ShownTimer(owner, 1_000, () -> { });

    @AfterEach
    void stop() {
        timer.stop();
    }

    /// An owner that wants ticks but is not showing, as a component of a closed window, keeps the timer stopped.
    @Test
    void wantedButNotShowingNeverRuns() {
        timer.want(true);
        assertThat(timer.wanted()).isTrue();
        assertThat(timer.running()).isFalse();
    }

    /// Wanted and showing, in either order of the two, the timer runs.
    @Test
    void wantedAndShownRuns() {
        timer.want(true);
        owner.showing(true);
        assertThat(timer.running()).as("wanted, then shown").isTrue();
        ShownTimer other = new ShownTimer(owner, 1_000, () -> { });
        try {
            other.want(true);
            assertThat(other.running()).as("shown, then wanted").isTrue();
        } finally {
            other.stop();
        }
    }

    /// Hiding a showing owner stops the timer and keeps the want.
    @Test
    void hiddenStops() {
        owner.showing(true);
        timer.want(true);
        owner.showing(false);
        assertThat(timer.running()).isFalse();
        assertThat(timer.wanted()).isTrue();
    }

    /// `want(false)` and `stop()` stop a running timer while the owner still shows.
    @Test
    void unwantedStops() {
        owner.showing(true);
        timer.want(true);
        timer.want(false);
        assertThat(timer.running()).isFalse();
        timer.want(true);
        timer.stop();
        assertThat(timer.running()).isFalse();
        assertThat(timer.wanted()).isFalse();
    }

    /// Showing again resumes a timer that is still wanted, and leaves one unwanted while hidden stopped.
    @Test
    void showingAgainResumesOnlyIfStillWanted() {
        owner.showing(true);
        timer.want(true);
        owner.showing(false);
        owner.showing(true);
        assertThat(timer.running()).as("still wanted").isTrue();
        owner.showing(false);
        timer.want(false);
        owner.showing(true);
        assertThat(timer.running()).as("unwanted while hidden").isFalse();
    }
}
