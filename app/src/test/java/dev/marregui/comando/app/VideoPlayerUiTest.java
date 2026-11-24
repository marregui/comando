package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.media.VideoPlayer;
import dev.marregui.comando.ui.widgets.video.VideoWidget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// An MP4 video played by JavaFX's media player in a window, as the side pane shows it. JavaFX needs a display, so
/// this runs in `./gradlew :app:uiTest`. The fixture `video/two-seconds.mp4` is 2.0 s of H.264 (baseline, yuv420p)
/// and AAC, made by ffmpeg.
/// - the video gets ready with its length, plays, advances, pauses, and at its end pauses back at its start, where the
///   seek bar still moves it
/// - a view hidden right after play pauses
/// - a file that is no video fails its readiness and the view says why
/// - closing twice is fine, and a closed player cancels its readiness and ignores play
/// - the widget loads its path again at each render
///
/// macOS decodes H.264 through AVFoundation, so there the video must play; Linux decodes it through the system's
/// libavcodec, which a machine may lack, so there a video the player refuses must at least say why.
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class VideoPlayerUiTest {

    /** The bound on a load or a state change: over the player's 10 s deadline for a file it cannot open. */
    private static final Duration WAIT = Duration.ofSeconds(20);

    /** The fixture's length, as ffprobe reads it. */
    private static final long FIXTURE_MILLIS = 2_000L;

    /** How far the player's length may differ from ffprobe's: one frame of the fixture's 15 per second and more. */
    private static final long LENGTH_SLACK_MILLIS = 150L;

    /** How long a state that must not change is watched: chosen, over two hops from the JavaFX thread to the EDT. */
    private static final Duration HOLD = Duration.ofSeconds(1);

    /**
     * The bound on a hold: the state must hold from the start, so a later stretch of it does not pass; under the
     * fixture's 2 s, after which a video that played ends paused.
     */
    private static final Duration HOLD_BOUND = HOLD.plusMillis(500);

    @TempDir
    private Path dir;

    private VideoPlayer player;

    private JFrame frame;

    @BeforeEach
    void openPlayer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            player = VideoPlayer.create();
            frame = new JFrame("video");
            frame.setUndecorated(true);
            frame.setAutoRequestFocus(false);
            frame.add(player.component());
            frame.setBounds(-5_000, 0, 320, 240);
            frame.setVisible(true);
        });
    }

    @AfterEach
    void closePlayer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            player.close();
            frame.dispose();
        });
    }

    /// The fixture gets ready with its 2 s length, plays and advances, pauses where it is, and played to its end
    /// pauses back at its start, where the seek bar still moves it: to its middle, 1 s in.
    @Test
    void theVideoPlaysPausesAndEndsAtItsStartWhereItStillSeeks() throws Exception {
        final Duration length = readyOrAbort(fixture());
        assertThat(length.toMillis())
                .isBetween(FIXTURE_MILLIS - LENGTH_SLACK_MILLIS, FIXTURE_MILLIS + LENGTH_SLACK_MILLIS);
        assertThat(onEdt(player::message)).isEmpty();

        SwingUtilities.invokeAndWait(player::toggle);
        Awaitility.await().atMost(WAIT).until(() -> onEdt(() -> player.playing() && player.positionMillis() > 0L));

        SwingUtilities.invokeAndWait(player::toggle);
        Awaitility.await().atMost(WAIT).until(() -> onEdt(() -> !player.playing()));
        final long pausedMillis = onEdt(player::positionMillis);
        assertThat(pausedMillis).isPositive();

        SwingUtilities.invokeAndWait(player::toggle);
        Awaitility.await().atMost(WAIT).until(() -> onEdt(player::playing));
        Awaitility.await().atMost(WAIT).until(() -> onEdt(() -> !player.playing() && player.positionMillis() == 0L));

        SwingUtilities.invokeAndWait(() -> seekBar().setValue(seekBar().getMaximum() / 2));
        final long middleMillis = FIXTURE_MILLIS / 2 - LENGTH_SLACK_MILLIS;
        Awaitility.await().atMost(WAIT).until(() -> onEdt(player::positionMillis), at -> at >= middleMillis);
        assertThat(onEdt(player::playing)).isFalse();
    }

    /// A view hidden right after play was asked, before the player said it plays, ends paused and stays so for 1 s.
    @Test
    void aViewHiddenRightAfterPlayPauses() throws Exception {
        readyOrAbort(fixture());

        SwingUtilities.invokeAndWait(() -> {
            player.toggle();
            frame.setVisible(false);
        });

        Awaitility.await().atMost(HOLD_BOUND).during(HOLD).until(() -> onEdt(() -> !player.playing()));
    }

    /// A file named `.mp4` that holds text fails its readiness, by the player's error or by its 10 s deadline, and
    /// the view shows the reason.
    @Test
    void aFileThatIsNoVideoSaysWhyItCannotPlay() throws Exception {
        final Path bad = Files.writeString(dir.resolve("bad.mp4"), "not a video at all");
        final CompletableFuture<Duration> ready = onEdt(() -> {
            player.load(bad);
            return player.ready();
        });

        assertThatThrownBy(() -> ready.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
        Awaitility.await().atMost(WAIT)
                .until(() -> onEdt(() -> player.message().startsWith("Cannot play this video: ")));
        assertThat(onEdt(player::playing)).isFalse();
    }

    /// Closing twice is fine; the readiness of a closed player is cancelled, and play asked after it does not play
    /// for 1 s.
    @Test
    void aClosedPlayerCancelsItsReadinessAndIgnoresPlay() throws Exception {
        final Path video = fixture();
        final CompletableFuture<Duration> ready = onEdt(() -> {
            player.load(video);
            player.close();
            player.close();
            player.toggle();
            return player.ready();
        });

        assertThat(ready).isCancelled();
        Awaitility.await().atMost(HOLD_BOUND).during(HOLD).until(() -> onEdt(() -> !player.playing()));
    }

    /// The widget loads its path again at each render, as a view shown again shows the file as it is now; a widget
    /// whose player cannot be made shows the reason in its place.
    @Test
    void theWidgetLoadsItsPathAtEachRenderAndSaysWhyWithoutAPlayer() throws Exception {
        final Path video = fixture();
        final AtomicReference<CompletableFuture<Duration>> first = new AtomicReference<>();
        final CompletableFuture<Duration> second = onEdt(() -> {
            final VideoWidget widget = new VideoWidget(() -> player);
            widget.render(new ViewSpec.Video(video.toString()));
            first.set(player.ready());
            widget.render(new ViewSpec.Video(video.toString()));
            return player.ready();
        });
        assertThat(second).isNotSameAs(first.get());
        assertThat(first.get()).isCancelled();

        final String why = onEdt(() -> {
            final VideoWidget none = new VideoWidget(() -> {
                throw new UnsupportedOperationException("no display here");
            });
            return ((JLabel) named(none.component(), VideoWidget.UNAVAILABLE)).getText();
        });
        assertThat(why).isEqualTo("Cannot play videos here: no display here");
    }

    private static Component named(final Container in, final String name) {
        for (final Component c : in.getComponents()) {
            if (name.equals(c.getName())) {
                return c;
            }
            if (c instanceof final Container inner) {
                final Component found = named(inner, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JSlider slider(final Container in) {
        for (final Component c : in.getComponents()) {
            if (c instanceof final JSlider found) {
                return found;
            }
            if (c instanceof final Container inner) {
                final JSlider found = slider(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static <T> T onEdt(final Supplier<T> read) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(read.get()));
        return out.get();
    }

    /// Loads `video` and waits until it can play, returning its length; on a machine that cannot decode it, other than
    /// macOS, the test is skipped once the view says why.
    private Duration readyOrAbort(final Path video) throws Exception {
        final CompletableFuture<Duration> ready = onEdt(() -> {
            player.load(video);
            return player.ready();
        });
        try {
            return ready.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            final String why = onEdt(player::message);
            assertThat(why).startsWith("Cannot play this video: ");
            assertThat(isMac()).as("macOS plays H.264, yet: " + why).isFalse();
            return Assumptions.abort("this machine does not decode H.264, and the view says so: " + why);
        }
    }

    private JSlider seekBar() {
        return slider(player.component());
    }

    private Path fixture() throws Exception {
        final Path out = dir.resolve("two-seconds.mp4");
        try (final InputStream in = VideoPlayerUiTest.class.getResourceAsStream("/video/two-seconds.mp4")) {
            assertThat(in).isNotNull();
            Files.copy(in, out);
        }
        return out;
    }
}
