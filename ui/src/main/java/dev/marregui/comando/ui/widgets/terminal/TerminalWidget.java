package dev.marregui.comando.ui.widgets.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A terminal: the user's login shell on a pseudo-terminal, shown by JediTerm. For what Claude does not
 * do: interactive logins, git by hand, ssh. The shell starts on the first {@link #start}; when it exits,
 * a bar offers a restart.
 */
public final class TerminalWidget {

    private static final System.Logger LOG = System.getLogger(TerminalWidget.class.getName());

    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel exitedBar = new JPanel(new BorderLayout(Theme.GAP, 0));
    private final JLabel exitedLabel = new JLabel();
    private Path directory;
    private JediTermWidget term;
    private Pty pty;

    public TerminalWidget(Path directory) {
        this.directory = directory;
        exitedBar.setBorder(BorderFactory.createEmptyBorder(4, Theme.PAD, 4, Theme.PAD));
        JButton restart = new JButton("Restart");
        restart.addActionListener(e -> restart());
        exitedLabel.setForeground(Theme.MUTED);
        exitedBar.add(exitedLabel, BorderLayout.CENTER);
        exitedBar.add(restart, BorderLayout.EAST);
        exitedBar.setVisible(false);
        root.add(exitedBar, BorderLayout.NORTH);
    }

    public JComponent component() {
        return root;
    }

    public boolean isStarted() {
        return term != null;
    }

    public boolean isRunning() {
        return pty != null && pty.process().isAlive();
    }

    /** Starts the shell when it is not running; on the EDT. */
    public void start() {
        if (isRunning()) {
            return;
        }
        if (term != null) {
            root.remove(term);
            term.close();
        }
        term = new JediTermWidget(120, 30, new Settings());
        try {
            pty = Pty.start(Pty.loginShell(), directory, 120, 30);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot start a shell in " + directory + ": " + e.getMessage());
            exitedLabel.setText("Cannot start a shell: " + e.getMessage());
            exitedBar.setVisible(true);
            return;
        }
        term.setTtyConnector(pty);
        term.start();
        exitedBar.setVisible(false);
        root.add(term, BorderLayout.CENTER);
        root.revalidate();
        Pty watched = pty;
        Thread.ofVirtual().name("terminal-exit").start(() -> {
            try {
                int code = watched.process().waitFor();
                Edt.later(() -> exited(watched, code));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void exited(Pty which, int code) {
        if (which != pty) {
            return;
        }
        exitedLabel.setText("The shell exited with code " + code + ".");
        exitedBar.setVisible(true);
        root.revalidate();
    }

    public void restart() {
        close();
        start();
    }

    /** Where the next start opens the shell. */
    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    /** Types {@code text} into the shell, as if pasted. */
    public void send(String text) {
        if (!isRunning()) {
            start();
        }
        if (pty != null) {
            try {
                pty.write(text);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot write to the shell: " + e.getMessage());
            }
        }
    }

    public void focus() {
        if (term != null) {
            term.requestFocusInWindow();
        }
    }

    /** Ends the shell and frees the emulator. */
    public void close() {
        Pty p = pty;
        pty = null;
        if (term != null) {
            root.remove(term);
            term.close();
            term = null;
        }
        if (p != null && p.process().isAlive()) {
            p.process().destroy();
        }
    }

    List<String> command() {
        return Pty.loginShell();
    }

    /** Colours and font from the theme. */
    private static final class Settings extends DefaultSettingsProvider {
        @Override
        public Font getTerminalFont() {
            return Theme.mono();
        }

        @Override
        public float getTerminalFontSize() {
            return Theme.mono().getSize2D();
        }

        @Override
        public TerminalColor getDefaultForeground() {
            return TerminalColor.rgb(0xDF, 0xE1, 0xE5);
        }

        @Override
        public TerminalColor getDefaultBackground() {
            return TerminalColor.rgb(0x1E, 0x1F, 0x22);
        }

        /** Deprecated in JediTerm, yet still read for text written before the first paint. */
        @Override
        @SuppressWarnings("deprecation")
        public TextStyle getDefaultStyle() {
            return new TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        @Override
        public boolean audibleBell() {
            return false;
        }

        @Override
        public int getBufferMaxLinesCount() {
            return 20_000;
        }
    }
}
