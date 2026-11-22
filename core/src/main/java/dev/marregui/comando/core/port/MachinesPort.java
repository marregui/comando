package dev.marregui.comando.core.port;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * This machine and the machines paired with it (record 189): the Machines dialog and the {@code machines} tool read
 * and change them. Pairing stores another machine's token in {@code ~/.comando-remotes.json}; the border gateway reads
 * a change at its next look. Never blocks on the network.
 */
public interface MachinesPort {

    /**
     * A machine id (record 189 §2.2): lowercase letters, digits and dashes, neither first nor last, as a slug reads,
     * so {@code <slug>@<machine>} reads back; at most {@link #MACHINE_ID_CHARS} characters.
     */
    Pattern MACHINE_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?");
    /** A machine id's length limit: a link id {@code main@<machine>} stays well within frontear's 64. */
    int MACHINE_ID_CHARS = 32;
    /** A key's fingerprint: the SHA-256 of its public key's X.509 encoding, 64 lowercase hex digits. */
    Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    /** What another machine may do here: {@link #PEER}, the default, or {@link #OVERSEER}, the user's authority. */
    enum Grant {
        PEER,
        OVERSEER;

        /** The grant as the remotes file, the {@code machines} tool and the Machines window write it. */
        public String text() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The grant {@code text} names, in any case; empty when it names none. */
        public static Optional<Grant> of(final String text) {
            for (final Grant g : values()) {
                if (g.text().equalsIgnoreCase(text == null ? "" : text.strip())) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A pairing token, {@code comando-pair:1:<machine>:<host>:<port>:<fingerprint>}: the line one machine shows and
     * another pairs with (record 189 §2.2).
     */
    record Token(String machine, String host, int port, String fingerprint) {

        /** A token's start: its kind and its version. */
        public static final String PREFIX = "comando-pair:1:";
        private static final int LAST_PORT = 65_535;

        /**
         * The token {@code text} reads as. Refused with an {@link IllegalArgumentException} saying why when it is not
         * of this version, or its machine, host, port or fingerprint does not read; the host is the text between the
         * machine and the port, so an IPv6 literal reads whole.
         */
        public static Token parse(final String text) {
            final String t = text == null ? "" : text.strip();
            if (!t.startsWith(PREFIX)) {
                throw new IllegalArgumentException("not a pairing token of this version [token=" + t + ", expected="
                        + PREFIX + "<machine>:<host>:<port>:<fingerprint>]");
            }
            final String rest = t.substring(PREFIX.length());
            final int afterMachine = rest.indexOf(':');
            final int beforeFingerprint = rest.lastIndexOf(':');
            final int beforePort = beforeFingerprint < 0 ? -1 : rest.lastIndexOf(':', beforeFingerprint - 1);
            if (afterMachine < 0 || beforePort <= afterMachine) {
                throw new IllegalArgumentException("pairing token has too few parts [token=" + t + ']');
            }
            final String machine = rest.substring(0, afterMachine);
            final String host = rest.substring(afterMachine + 1, beforePort);
            final String portText = rest.substring(beforePort + 1, beforeFingerprint);
            final String fingerprint = rest.substring(beforeFingerprint + 1);
            if (!MACHINE_ID.matcher(machine).matches()) {
                throw new IllegalArgumentException("pairing token names no machine id [machine=" + machine
                        + ", allowed=" + MACHINE_ID.pattern() + ']');
            }
            if (host.isEmpty()) {
                throw new IllegalArgumentException("pairing token has no host: its machine listens nowhere yet"
                        + " [machine=" + machine + ']');
            }
            final int port;
            try {
                port = Integer.parseInt(portText);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("pairing token's port is not a number [port=" + portText + ']', e);
            }
            if (port < 1 || port > LAST_PORT) {
                throw new IllegalArgumentException("pairing token's port is out of range [port=" + port + ", max="
                        + LAST_PORT + ']');
            }
            if (!FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("pairing token's fingerprint is not 64 lowercase hex digits"
                        + " [fingerprint=" + fingerprint + ']');
            }
            return new Token(machine, host, port, fingerprint);
        }

        /** The token's line, which {@link #parse} reads back. */
        public String text() {
            return PREFIX + machine + ':' + host + ':' + port + ':' + fingerprint;
        }
    }

    /**
     * This machine: its id, the address its border gateway listens on (empty while none is set), the port, its key's
     * fingerprint (SHA-256 of the public key, lowercase hex), and its token, the line another machine pairs with.
     */
    record Self(String machine, String host, int port, String fingerprint, String token) {
    }

    /**
     * A paired machine: its id, host, port, fingerprint, grant; whether its border gateway link is connected now, and
     * when it was last connected, epoch milliseconds, 0 for never.
     */
    record Paired(String machine, String host, int port, String fingerprint, Grant grant, boolean connected,
            long lastConnectedMillis) {
    }

    Self self();

    /** The paired machines, by id. */
    List<Paired> paired();

    /**
     * Sets the address this machine's border gateway listens on, empty to stop listening; refused with an
     * {@link IllegalArgumentException} when it is the wildcard address or not an address of this machine.
     */
    void listen(String host);

    /**
     * Pairs the machine of {@code token}, with {@code grant}; the token's fingerprint is the one the user confirmed.
     * Refused with an {@link IllegalArgumentException} when the token does not read or names this machine.
     */
    Paired pair(String token, Grant grant);

    /**
     * Unpairs {@code machine}, saved at once: at the border gateway's next look its link closes and its instances are
     * no longer reachable; false when not paired.
     */
    boolean unpair(String machine);

    /** For comando without frontear (no node), and for the replay tests: this machine unnamed, nothing paired. */
    MachinesPort NONE = new MachinesPort() {
        @Override
        public Self self() {
            return new Self("", "", 0, "", "");
        }

        @Override
        public List<Paired> paired() {
            return List.of();
        }

        @Override
        public void listen(final String host) {
            throw new IllegalArgumentException("no frontear in this window");
        }

        @Override
        public Paired pair(final String token, final Grant grant) {
            throw new IllegalArgumentException("no frontear in this window");
        }

        @Override
        public boolean unpair(final String machine) {
            return false;
        }
    };
}
