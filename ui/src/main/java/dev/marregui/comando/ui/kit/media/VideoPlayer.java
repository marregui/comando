package dev.marregui.comando.ui.kit.media;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.fx.FxToolkit;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A video file played by JavaFX's {@link MediaPlayer} inside a Swing panel, with a play/pause button, a seek bar and
 * the time under the picture. The player and its view live on the JavaFX application thread, which every call hands
 * its work to; the panel, the controls and what they show live on the EDT, which the player's events are posted to.
 * A click on the picture, or the space bar while the picture has the keyboard, plays or pauses. A video keeps playing
 * while its view does not show (another tab, another panel); of the players that share one {@link OnePlaying}, the one
 * asked to play last plays, and the others pause.
 */
public final class VideoPlayer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VideoPlayer.class.getName());

    /** Steps of the seek bar: one per mille of the video's length, finer than a pixel of any side pane. */
    private static final int SEEK_STEPS = 1000;

    /** The step the time label follows, in milliseconds: four updates a second for a label of whole seconds. */
    private static final long POSITION_STEP_MILLIS = 250L;

    /**
     * How long a file may take to open before the view says it cannot play it: macOS reports neither readiness nor an
     * error for a file it cannot parse. The first load of a process, which starts the media stack, took 6 s on the
     * MacBook Pro this was written on (2026-11-24); a file that opens at all opened in under 1 s after it.
     */
    private static final long OPEN_DEADLINE_MILLIS = 10_000L;

    private final JPanel root = new JPanel(new BorderLayout());

    private final JFXPanel surface = new JFXPanel();

    private final JButton play;

    private final JSlider seek = new JSlider(0, SEEK_STEPS, 0);

    private final JLabel time = new JLabel();

    private final JLabel message = new JLabel();

    /** The players of which one plays at a time; this one among them. */
    private final OnePlaying one;

    /** Written once by {@link #close()} on the EDT; read on the EDT and on the JavaFX thread before each use. */
    private volatile boolean closed;

    /** Whether the slider is being moved by the player rather than the user; EDT only. */
    private boolean following;

    /** Whether the player is playing, as its last status event said; EDT only. */
    private boolean playing;

    /** The loaded video's length, 0 until it is ready; EDT only. */
    private long durationMillis;

    /** The position shown, in steps of {@link #POSITION_STEP_MILLIS}; EDT only. */
    private long positionMillis;

    /** About the video loaded last: completes with its length, or fails with why it cannot play; EDT replaces it. */
    private CompletableFuture<Duration> ready = new CompletableFuture<>();

    /** The player of the loaded video; JavaFX thread only, null before a load and after close. */
    private MediaPlayer player;

    /** The view the player draws in; JavaFX thread only, null until made and after close. */
    private MediaView view;

    /** The position last posted to the EDT, in steps of {@link #POSITION_STEP_MILLIS}; JavaFX thread only. */
    private long postedStep = -1L;

    /** The load {@link #player} plays; JavaFX thread only, null while there is no player. */
    private CompletableFuture<Duration> playerLoad;

    /** The load whose failure was told; JavaFX thread only, null before the first failure. */
    private CompletableFuture<Duration> told;

    private VideoPlayer(final OnePlaying one) {
        this.one = one;
        play = ToolButton.of("play", "Play", this::toggle);
        message.setForeground(Theme.MUTED);
        message.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP, 2, Theme.GAP));
        message.setVisible(false);
        seek.setFocusable(false);
        seek.addChangeListener(_ -> {
            if (!following && durationMillis > 0L) {
                final double fraction = seek.getValue() / (double) SEEK_STEPS;
                onFx(() -> seekOnFx(player.getTotalDuration().multiply(fraction)));
            }
        });
        final JPanel controls = new JPanel(new BorderLayout(Theme.GAP, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP, 2, Theme.GAP));
        controls.add(play, BorderLayout.WEST);
        controls.add(seek, BorderLayout.CENTER);
        controls.add(time, BorderLayout.EAST);
        final JPanel south = new JPanel(new BorderLayout());
        south.add(controls, BorderLayout.CENTER);
        south.add(message, BorderLayout.SOUTH);
        root.add(surface, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        showControls();
    }

    /**
     * A new player showing nothing until a video is loaded, JavaFX started on the first call; of the players made
     * with {@code one}, the one asked to play last plays. EDT.
     *
     * @throws UnsupportedOperationException when this process has no display, or JavaFX cannot start
     */
    public static VideoPlayer create(final OnePlaying one) {
        FxToolkit.start();
        final VideoPlayer v = new VideoPlayer(one);
        Platform.runLater(v::build);
        return v;
    }

    /** The component the view lays out. */
    public JComponent component() {
        return root;
    }

    /**
     * Shows {@code file} paused at its start, in place of any video loaded before; what {@link #ready()} returns from
     * now on is about this file. EDT.
     */
    public void load(final Path file) {
        ready.cancel(false);
        final CompletableFuture<Duration> loading = new CompletableFuture<>();
        ready = loading;
        durationMillis = 0L;
        positionMillis = 0L;
        playing = false;
        showMessage("");
        showControls();
        // toUri asks the file system whether the path is a folder: not on the EDT
        onFxAlways(() -> open(file.toAbsolutePath().toUri().toString(), loading));
    }

    /** Plays when paused or stopped, pauses when playing. EDT. */
    public void toggle() {
        onFx(this::toggleOnFx);
    }

    /**
     * Completes with the loaded video's length once it can play, or fails with the player's reason when it cannot (a
     * codec the platform does not decode); cancelled by the next {@link #load} and by {@link #close()}. It completes
     * on the JavaFX thread, so a caller off the EDT may wait on it with a timeout. EDT.
     */
    @TestOnly
    public CompletableFuture<Duration> ready() {
        return ready;
    }

    /** Whether the video plays, as the player last said. EDT. */
    @TestOnly
    public boolean playing() {
        return playing;
    }

    /** The position shown under the picture, in milliseconds, in steps of a quarter second. EDT. */
    @TestOnly
    public long positionMillis() {
        return positionMillis;
    }

    /** The reason shown under the controls, empty when none. EDT. */
    @TestOnly
    public String message() {
        return message.isVisible() ? message.getText() : "";
    }

    /** Stops the video and releases its player; later calls do nothing. Idempotent. EDT. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        one.left(this);
        ready.cancel(false);
        try {
            Platform.runLater(() -> {
                release();
                view = null;
                surface.setScene(null);
            });
        } catch (final IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot release the video player, the JavaFX toolkit has ended", e);
        }
    }

    /** Pauses a playing video; does nothing to one that is not. EDT. */
    void pause() {
        onFx(() -> player.pause());
    }

    /** The video's time as {@code m:ss}, or {@code h:mm:ss} from an hour on. */
    static String clock(final long millis) {
        final long s = Math.max(0L, millis) / 1000L;
        return s >= 3600L
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L)
                : String.format(Locale.ROOT, "%d:%02d", s / 60L, s % 60L);
    }

    /** Runs {@code task} on the JavaFX thread while a player exists and this view is not closed. */
    private void onFx(final Runnable task) {
        onFxAlways(() -> {
            if (player != null) {
                task.run();
            }
        });
    }

    /** Runs {@code task} on the JavaFX thread while this view is not closed. */
    private void onFxAlways(final Runnable task) {
        if (closed) {
            return;
        }
        Platform.runLater(() -> {
            if (!closed) {
                task.run();
            }
        });
    }

    /** Makes the view, on the JavaFX thread. */
    private void build() {
        if (closed) {
            return;
        }
        view = new MediaView();
        view.setPreserveRatio(true);
        view.setOnMouseClicked(_ -> toggleOnFx());
        final Scene scene = new Scene(new StackPane(view), Color.BLACK);
        view.fitWidthProperty().bind(scene.widthProperty());
        view.fitHeightProperty().bind(scene.heightProperty());
        scene.setOnKeyPressed(k -> {
            if (k.getCode() == KeyCode.SPACE) {
                toggleOnFx();
                k.consume();
            }
        });
        surface.setScene(scene);
        if (player != null) {
            view.setMediaPlayer(player);
        }
    }

    /** Opens {@code uri} in a new player, releasing the one before; JavaFX thread. */
    private void open(final String uri, final CompletableFuture<Duration> loading) {
        release();
        postedStep = -1L;
        final Media media;
        final MediaPlayer p;
        try {
            media = new Media(uri);
            p = new MediaPlayer(media);
        } catch (final MediaException | IllegalArgumentException | UnsupportedOperationException e) {
            failed(loading, uri, e);
            return;
        }
        player = p;
        playerLoad = loading;
        // a file the platform cannot read fails on the media, not on its player
        media.setOnError(() -> failed(loading, uri, media.getError()));
        p.setOnReady(() -> {
            final long millis = (long) p.getTotalDuration().toMillis();
            // ready after its deadline: the view said already that it cannot play the file
            if (!loading.complete(Duration.ofMillis(millis))) {
                return;
            }
            Edt.later(() -> {
                if (ready == loading) {
                    durationMillis = millis;
                    showControls();
                }
            });
        });
        p.setOnError(() -> failed(loading, uri, p.getError()));
        // at its end the video goes back to its start, paused: a stopped player ignores the seek bar
        p.setOnEndOfMedia(() -> {
            p.pause();
            seekOnFx(p.getStartTime());
        });
        p.statusProperty().addListener((_, _, status) -> {
            if (status == MediaPlayer.Status.HALTED) {
                failed(loading, uri, p.getError());
            }
            final boolean on = status == MediaPlayer.Status.PLAYING;
            Edt.later(() -> {
                if (ready == loading) {
                    playing = on;
                    showControls();
                }
            });
        });
        // the current time is followed while the video plays; paused, it can still report a seek asked before the last
        // one, so a paused position comes from the seek itself (seekOnFx)
        p.currentTimeProperty().addListener((_, _, now) -> {
            if (p.getStatus() == MediaPlayer.Status.PLAYING) {
                post((long) now.toMillis());
            }
        });
        if (view != null) {
            view.setMediaPlayer(p);
        }
        final PauseTransition deadline = new PauseTransition(javafx.util.Duration.millis(OPEN_DEADLINE_MILLIS));
        deadline.setOnFinished(_ -> {
            if (!loading.isDone() && player == p) {
                failed(loading, uri, new IllegalStateException(
                        "the player did not open it within " + OPEN_DEADLINE_MILLIS / 1000L + " s"));
                release();
            }
        });
        deadline.play();
    }

    /** The player cannot play {@code uri}: the future fails and the view says why. JavaFX thread. */
    private void failed(final CompletableFuture<Duration> loading, final String uri, final Exception e) {
        // the media, its player and the player's halt can each report the one failure, before or after readiness: it
        // is told once per load
        if (told == loading) {
            return;
        }
        told = loading;
        final String why = e == null || e.getMessage() == null ? "the player gave no reason" : e.getMessage();
        LOG.log(System.Logger.Level.WARNING,
                "cannot play the video, its view says why [uri=" + uri + ", cause=" + why + ']');
        loading.completeExceptionally(e != null ? e : new IllegalStateException(why));
        Edt.later(() -> {
            if (ready == loading) {
                showMessage("Cannot play this video: " + why);
            }
        });
    }

    /**
     * Moves the player to {@code to} and shows it there: a paused player's current time is not followed, so the
     * position is posted from the seek itself. JavaFX thread, with a player.
     */
    private void seekOnFx(final javafx.util.Duration to) {
        player.seek(to);
        post((long) to.toMillis());
    }

    /** Shows {@code millis} as the position of the load playing, at {@link #POSITION_STEP_MILLIS}; JavaFX thread. */
    private void post(final long millis) {
        final long step = millis / POSITION_STEP_MILLIS;
        final CompletableFuture<Duration> loading = playerLoad;
        if (step == postedStep || loading == null) {
            return;
        }
        postedStep = step;
        Edt.later(() -> {
            if (ready == loading) {
                positionMillis = step * POSITION_STEP_MILLIS;
                showControls();
            }
        });
    }

    /** Plays or pauses; a player that cannot play (not ready, halted) does neither. JavaFX thread. */
    private void toggleOnFx() {
        if (player == null) {
            return;
        }
        final MediaPlayer.Status status = player.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            player.pause();
        } else if (status == MediaPlayer.Status.READY || status == MediaPlayer.Status.PAUSED
                || status == MediaPlayer.Status.STOPPED) {
            // a player not yet ready, or halted, does not play, and pauses no other
            player.play();
            // the one asked last plays: the video that played before pauses
            Edt.later(() -> {
                if (!closed) {
                    one.started(this);
                }
            });
        }
    }

    /** Lets go of the player, then disposes of it; JavaFX thread. */
    private void release() {
        if (player == null) {
            return;
        }
        final MediaPlayer p = player;
        player = null;
        playerLoad = null;
        if (view != null) {
            view.setMediaPlayer(null);
        }
        p.dispose();
    }

    /** Shows {@code text} under the controls, or hides the line when empty; EDT. */
    private void showMessage(final String text) {
        message.setText(text);
        message.setVisible(!text.isEmpty());
    }

    /** Sets the button, the seek bar and the time to what the player last said; EDT. */
    private void showControls() {
        play.setIcon(Icons.get(playing ? "pause" : "play"));
        play.setToolTipText(playing ? "Pause" : "Play");
        play.getAccessibleContext().setAccessibleName(play.getToolTipText());
        play.setEnabled(durationMillis > 0L);
        seek.setEnabled(durationMillis > 0L);
        following = true;
        try {
            final long at = durationMillis > 0L ? positionMillis * SEEK_STEPS / durationMillis : 0L;
            seek.setValue((int) Math.min(SEEK_STEPS, at));
        } finally {
            following = false;
        }
        time.setText(clock(positionMillis) + " / " + clock(durationMillis));
    }
}
