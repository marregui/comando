package dev.marregui.comando.ui.widgets.video;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.media.VideoPlayer;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;

/**
 * A video file played in the view, its path above the picture. Where no player can be made (no display, JavaFX does
 * not start) the view says why instead; the player is released when the view leaves.
 */
public final class VideoWidget implements Widget<ViewSpec.Video> {

    /** The name of the label that says why no video plays here. */
    public static final String UNAVAILABLE = "video-unavailable";

    private static final System.Logger LOG = System.getLogger(VideoWidget.class.getName());

    private final JPanel root = new JPanel(new BorderLayout());

    private final JLabel pathLabel = new JLabel();

    /** The player; null when none could be made. */
    private final VideoPlayer player;

    /** A video view whose player {@code players} makes, on the EDT. */
    public VideoWidget(final Supplier<VideoPlayer> players) {
        pathLabel.setForeground(Theme.MUTED);
        pathLabel.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP, 2, Theme.GAP));
        root.add(pathLabel, BorderLayout.NORTH);
        VideoPlayer made;
        try {
            made = players.get();
        } catch (final UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "cannot make a video player, the view says why instead [cause=" + e.getMessage() + ']');
            made = null;
            final JLabel why = new JLabel("Cannot play videos here: " + e.getMessage(), SwingConstants.CENTER);
            why.setName(UNAVAILABLE);
            why.setForeground(Theme.MUTED);
            root.add(why, BorderLayout.CENTER);
        }
        player = made;
        if (player != null) {
            root.add(player.component(), BorderLayout.CENTER);
        }
    }

    @Override
    public JComponent component() {
        return root;
    }

    /**
     * Loads the view's file, from its start: a view is rendered again only when it is shown again, and then shows the
     * file as it is now, which a session may have made anew.
     */
    @Override
    public void render(final ViewSpec.Video view) {
        pathLabel.setText(view.path());
        pathLabel.setToolTipText(view.path());
        if (player != null) {
            player.load(Path.of(view.path()));
        }
    }

    @Override
    public void unmount() {
        if (player != null) {
            player.close();
        }
    }
}
