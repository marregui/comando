package dev.marregui.comando.ui.dialogs.machines;

import java.awt.BorderLayout;
import java.awt.Component;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.core.port.MachinesPort.Grant;
import dev.marregui.comando.core.port.MachinesPort.Paired;
import dev.marregui.comando.core.port.MachinesPort.Token;
import dev.marregui.comando.ui.dialogs.FormRows;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.format.MachineTexts;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * This machine and the machines paired with it (record 189), as a list beside a form: this machine's id, the address
 * its border gateway listens on, which the user sets or clears, its port, its fingerprint and its pairing token, to
 * copy to the other machine; the selected paired machine's address, grant, connection and fingerprint; and a field for
 * another machine's token, the fingerprint it holds shown for the user to compare before pairing, with the grant to
 * give it, {@code peer} unless the user picks {@code overseer}. Unpairing asks first. Every call to the port runs on an
 * errand, off the EDT, and its answer comes back on the EDT, the buttons waiting meanwhile; what the port refuses shows
 * in red in the message line, and after each change the machines are read again. EDT only.
 */
public final class MachinesEditor {

    /** What the message line says when the port failed other than by refusing: the errand logged why. */
    static final String FAILED = "The call failed; comando's log says why";

    /** A port call's answer: its value, or what the port refused; neither when it failed otherwise. */
    private record Answer<T>(T value, String refused, boolean ok) {
    }

    /** This machine and its paired ones, read in one errand. */
    private record Look(MachinesPort.Self self, List<Paired> paired) {
    }

    private final MachinesPort port;
    private final ResourceActions.Questions questions;
    private final Consumer<String> copy;
    private final DefaultListModel<Paired> rows = new DefaultListModel<>();
    private final JList<Paired> list = new JList<>(rows);
    private final JLabel machine = new JLabel(" ");
    private final JTextField listen = new JTextField(24);
    private final JButton setListen = FormRows.tool("check", "Listen on this address; empty stops listening");
    private final JLabel portShown = new JLabel(" ");
    private final JLabel fingerprint = new JLabel(" ");
    private final JTextField token = new JTextField(24);
    private final JButton copyToken = FormRows.tool("copy", "Copy this machine's token, to pair it on the other one");
    private final JLabel address = new JLabel(" ");
    private final JLabel grant = new JLabel(" ");
    private final JLabel connection = new JLabel(" ");
    private final JLabel theirFingerprint = new JLabel(" ");
    private final JTextField theirToken = new JTextField(24);
    private final JLabel holds = new JLabel(" ");
    private final JLabel holdsFingerprint = new JLabel(" ");
    private final JComboBox<Grant> grantChoice = new JComboBox<>(Grant.values());
    private final JButton pair = new JButton("Pair");
    private final JButton unpair;
    private final FormRows form = new FormRows();
    private final JPanel body = new JPanel(new BorderLayout(Theme.GAP, Theme.GAP));
    private MachinesPort.Self self = MachinesPort.NONE.self();
    /** The machine to select once the list holds it; empty when none is asked for. */
    private String wanted = "";
    /** What the port refused last, red in the message line until the next call; empty when nothing. */
    private String refused = "";
    /** What the last change did, grey in the message line; empty when nothing. */
    private String note = "";
    /** Whether a call to the port is under way: the buttons wait for its answer. */
    private boolean busy;

    /**
     * Shows the machines {@code port} holds, read at once off the EDT; {@code questions} asks before pairing and
     * unpairing, {@code copy} takes this machine's token.
     */
    public MachinesEditor(
            final MachinesPort port,
            final ResourceActions.Questions questions,
            final Consumer<String> copy
    ) {
        this.port = port;
        this.questions = questions;
        this.copy = copy;
        unpair = FormRows.tool("unplug", "Unpair the selected machine: it reaches this one no more");
        build();
        enable();
        look();
    }

    public JComponent component() {
        return body;
    }

    /** The Pair button, which the window shows at its bottom. */
    public JButton pairButton() {
        return pair;
    }

    /** Selects the paired machine {@code id}, now or once the list holds it; nothing when empty. */
    public void select(final String id) {
        wanted = id == null ? "" : id;
        FormRows.select(list, rows, p -> p.machine().equals(wanted));
    }

    /** The message line's text, for tests. */
    String messageShown() {
        return form.messageShown();
    }

    /** {@code g} as the user reads it: {@code peer} or {@code overseer}. */
    static String name(final Grant g) {
        return g.text();
    }

    // ---- the calls

    /** Reads this machine and its paired ones again. */
    private void look() {
        call("look", () -> new Look(port.self(), port.paired()), this::show);
    }

    void setListen() {
        final String host = listen.getText().strip();
        call("listen", () -> {
            port.listen(host);
            return host;
        }, h -> {
            // the border gateway reads the machine file at its next look (record 189 §2.2)
            note = h.isEmpty() ? "Stopped listening: other machines cannot reach this one"
                    : "Listening on " + h + " from frontear's next look, within 5 s";
            look();
        });
    }

    void copyToken() {
        if (!self.token().isEmpty()) {
            copy.accept(self.token());
            refused = "";
            note = "Token copied: paste it in the other machine's Machines window";
            message();
        }
    }

    /** Pairs the machine the token names, with the grant chosen, after the user compared its fingerprint. */
    void pair() {
        final String text = theirToken.getText().strip();
        final Token t;
        try {
            t = Token.parse(text);
        } catch (final IllegalArgumentException e) {
            refused = "Not a pairing token: " + e.getMessage();
            message();
            return;
        }
        final Grant g = (Grant) grantChoice.getSelectedItem();
        final String question = "Pair with " + t.machine() + " at " + t.host() + ":" + t.port() + ", granted "
                + name(g) + "?\n\nIts fingerprint, which " + t.machine() + "'s Machines window shows too:\n"
                + t.fingerprint();
        if (questions.ask(body, Plain.text(question), MachinesDialog.TITLE, new Object[]{"Pair", "Cancel"}) != 0) {
            return;
        }
        call("pair", () -> port.pair(text, g), p -> {
            theirToken.setText("");
            wanted = p.machine();
            note = "Paired with " + p.machine() + ", granted " + name(p.grant());
            look();
        });
    }

    /** Unpairs the selected machine, after the user said so. */
    void unpair() {
        final Paired p = list.getSelectedValue();
        if (p == null) {
            return;
        }
        final String id = p.machine();
        final String question = "Unpair " + id + "? It reaches this machine no more, and pairing it again takes its "
                + "token.";
        if (questions.ask(body, Plain.text(question), MachinesDialog.TITLE, new Object[]{"Unpair", "Keep"}) != 0) {
            return;
        }
        call("unpair", () -> port.unpair(id), was -> {
            note = was ? "Unpaired " + id : id + " was not paired";
            look();
        });
    }

    /**
     * Runs {@code work} on an errand named for {@code what}, then {@code then} with its value on the EDT; a refusal
     * shows its message instead, and any other failure says so, the errand logging it.
     */
    private <T> void call(final String what, final Supplier<T> work, final Consumer<T> then) {
        busy = true;
        enable();
        UiErrands.start("machines-" + what, () -> {
            Answer<T> answer = new Answer<>(null, "", false);
            try {
                answer = new Answer<>(work.get(), "", true);
            } catch (final IllegalArgumentException e) {
                answer = new Answer<>(null, e.getMessage() == null ? "Refused" : e.getMessage(), false);
            } finally {
                final Answer<T> done = answer;
                SwingUtilities.invokeLater(() -> settle(done, then));
            }
        });
    }

    private <T> void settle(final Answer<T> a, final Consumer<T> then) {
        busy = false;
        if (a.ok()) {
            refused = "";
            then.accept(a.value());
        } else {
            refused = a.refused().isEmpty() ? FAILED : a.refused();
            note = "";
        }
        message();
        enable();
    }

    // ---- what shows

    private void show(final Look look) {
        self = look.self();
        machine.setText(self.machine().isEmpty() ? "none: frontear has not started here" : Plain.text(self.machine()));
        listen.setText(self.host());
        portShown.setText(self.port() == 0 ? "none" : String.valueOf(self.port()));
        fingerprint.setText(self.fingerprint().isEmpty() ? "none" : self.fingerprint());
        token.setText(self.token());
        token.setCaretPosition(0);
        final String selected = list.getSelectedValue() == null ? wanted : list.getSelectedValue().machine();
        rows.clear();
        look.paired().forEach(rows::addElement);
        if (!FormRows.any(rows, p -> p.machine().equals(selected))) {
            FormRows.selectFirst(list);
        } else {
            FormRows.select(list, rows, p -> p.machine().equals(selected));
        }
        load();
        enable();
    }

    /** The selected paired machine's facts beside the list. */
    private void load() {
        final Paired p = list.getSelectedValue();
        if (p == null) {
            for (final JLabel l : List.of(address, grant, connection, theirFingerprint)) {
                l.setText(" ");
            }
        } else {
            address.setText(Plain.text(p.host() + ":" + p.port()));
            grant.setText(MachineTexts.grant(p.grant()));
            connection.setText(MachineTexts.connection(p, System.currentTimeMillis(), ZoneId.systemDefault()));
            theirFingerprint.setText(Plain.text(p.fingerprint()));
        }
        enable();
    }

    /** What the token typed names, under it, as the user types it. */
    private void held() {
        final String text = theirToken.getText();
        final Optional<Token> h = text.isBlank() ? Optional.empty() : held(text);
        holds.setText(text.isBlank() ? " " : h.map(t -> Plain.text(t.machine() + " at " + t.host() + ":" + t.port()))
                .orElse("not a pairing token"));
        holdsFingerprint.setText(h.map(t -> Plain.text(t.fingerprint())).orElse(" "));
        enable();
    }

    /** What {@code text} names when it reads as a pairing token, to show the user before pairing. */
    private static Optional<Token> held(final String text) {
        try {
            return Optional.of(Token.parse(text));
        } catch (final IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private void message() {
        form.show(Map.of(), why -> true, refused, note);
    }

    private void enable() {
        setListen.setEnabled(!busy);
        copyToken.setEnabled(!busy && !self.token().isEmpty());
        pair.setEnabled(!busy && !theirToken.getText().isBlank());
        unpair.setEnabled(!busy && list.getSelectedValue() != null);
    }

    // ---- the form

    private void build() {
        setListen.addActionListener(e -> setListen());
        copyToken.addActionListener(e -> copyToken());
        pair.setToolTipText("Pair with the machine the token names, after comparing its fingerprint");
        pair.addActionListener(e -> pair());
        unpair.addActionListener(e -> unpair());
        final JPanel left = FormRows.listSide(list, Paired.class, (c, p, i, sel) -> {
            // a machine's id comes from its token, written on another machine: text, never HTML (own rule 6)
            Plain.only(c);
            c.setText(p.machine() + (p.connected() ? " · connected" : ""));
            c.setIcon(Icons.get("connection"));
            if (!sel && !p.connected()) {
                c.setForeground(Theme.MUTED);
            }
        }, "Paired machines", 220, this::load, unpair);
        token.setEditable(false);
        grantChoice.setSelectedItem(Grant.PEER);
        grantChoice.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int i,
                                                          final boolean sel, final boolean focus) {
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value instanceof Grant g) {
                    c.setText(name(g));
                }
                return c;
            }
        });
        for (final JLabel fact : List.of(machine, portShown, fingerprint, address, grant, connection,
                theirFingerprint, holds, holdsFingerprint)) {
            Plain.only(fact);
            fact.setForeground(Theme.MUTED_STRONG);
        }
        form.fact("Machine", machine, "This machine's id, which the other machines know it by");
        form.row("Listen on", FormRows.withButton(listen, setListen), listen, "listen",
                "Set the address the border gateway listens on for other machines; empty stops it",
                "A Tailscale or LAN address of this machine, never 0.0.0.0; empty: not listening",
                "The address frontear's border gateway, on the node of the instance main, listens on for the paired "
                        + "machines, with mutual TLS. It must be an address of this machine; the wildcard is refused. "
                        + "frontear reads it at its next look, within 5 s.");
        form.fact("Port", portShown, "The border gateway's port, port of machine.json");
        form.fact("Fingerprint", fingerprint, "SHA-256 of this machine's public key; the other machine compares it");
        form.row("Token", FormRows.withButton(token, copyToken), token, "token",
                "This machine's pairing token: copy it to the other machine's Machines window",
                "Give it to the other machine, which pairs with it",
                "One line naming this machine, its address, port and fingerprint. Pairing takes both machines: each "
                        + "pairs with the other's token.");
        form.fact("Address", address, "The selected machine's host and port");
        form.fact("Grant", grant, "What the selected machine's instances may do here");
        form.fact("Connection", connection, "Whether the selected machine is connected now, else when it last was");
        form.fact("Its fingerprint", theirFingerprint, "The fingerprint the selected machine's certificate must have");
        form.row("Pair with", theirToken, theirToken, "pair", "Paste the other machine's pairing token",
                "The other machine's token, from its Machines window",
                "Pairing stores the machine the token names, its address, port and fingerprint, after you compare "
                        + "the fingerprint with the one the other machine shows. A machine paired is trusted by that "
                        + "fingerprint alone, with no certificate authority.");
        form.fact("Token holds", holds, "The machine, host and port the token names");
        form.fact("Token's fingerprint", holdsFingerprint, "Compare it with the other machine's Fingerprint");
        form.row("Grant it", grantChoice, grantChoice, "grant", "What the machine's instances may do here",
                "peer: its questions run in plan mode; overseer: its main asks as this machine's overseer",
                "");
        form.footer(new JLabel(" "), "Note");
        FormRows.onEdit(this::held, List.of(theirToken));
        FormRows.lay(body, left, form.panel());
    }
}
