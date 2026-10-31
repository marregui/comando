package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.Coalescer;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * A sub-agent's tab (record 109): its conversation as a session's transcript, the same cards, links and strip, read
 * from the sub-agent's own file as it grows; over it, one line says whether it is followed, or why no longer. Nothing
 * is typed into it: a sub-agent takes no prompt.
 */
public final class AgentWidget implements Widget<ViewSpec.Agent>, Searchable, Copyable, Zoomable {

    private final TranscriptRegistry transcripts;
    private final TranscriptWidget transcript;
    private final JLabel state = new JLabel();
    private final JPanel root = new JPanel(new BorderLayout());
    private final Coalescer pull = new Coalescer(this::pull);
    private final Runnable listener = pull::request;
    private ViewSpec.Agent spec;
    private Transcript watched;

    public AgentWidget(WidgetContext context, TranscriptRegistry transcripts) {
        this.transcripts = transcripts;
        this.transcript = new TranscriptWidget(context);
        transcript.whenEmpty("The sub-agent has written nothing yet.");
        state.setName("agent-state");
        state.setForeground(Theme.MUTED);
        state.setBorder(BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD));
        root.add(state, BorderLayout.NORTH);
        root.add(transcript.component(), BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void render(ViewSpec.Agent view) {
        spec = view;
        state.setText(view.state());
        state.setVisible(!view.state().isEmpty());
        Transcript t = transcripts.of(TranscriptRegistry.subagentKey(view.session(), view.toolUseId()));
        if (t != watched) {
            if (watched != null) {
                watched.removeListener(listener);
            }
            watched = t;
            t.addListener(listener);
        }
        pull();
    }

    private void pull() {
        if (spec != null && watched != null) {
            // the session that started it: its folder is where the links are read from, and a nested sub-agent's
            // transcript link opens from it
            transcript.render(new TranscriptView(spec.session(), watched));
        }
    }

    @Override
    public void unmount() {
        if (watched != null) {
            watched.removeListener(listener);
            watched = null;
        }
    }

    /** The transcript shown; for tests. */
    public TranscriptWidget transcript() {
        return transcript;
    }

    @Override
    public int find(String text, boolean forward) {
        return transcript.find(text, forward);
    }

    @Override
    public void rewindFind() {
        transcript.rewindFind();
    }

    @Override
    public void clearFind() {
        transcript.clearFind();
    }

    @Override
    public String copyText() {
        return transcript.copyText();
    }

    @Override
    public boolean canCopy() {
        return transcript.canCopy();
    }

    @Override
    public void zoom(int steps) {
        transcript.zoom(steps);
    }

    @Override
    public void resetZoom() {
        transcript.resetZoom();
    }
}
