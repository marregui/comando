package dev.marregui.comando.frontear.tls;

import java.security.cert.Certificate;
import java.util.Map;
import java.util.Objects;

import dev.marregui.comando.core.port.MachinesPort;

/**
 * The fingerprints this machine trusts, each with its machine id: replaced whole, read lock-free, so a handshake sees
 * the set before a replacement or after it, never half of each. Empty until {@link #set} is first called.
 */
public final class Pins {

    /**
     * Written by the border gateway's look thread, which reconciles the pairings with the remotes file at each look;
     * read by every handshake's thread and by the gateway's recheck of its remote connections.
     */
    private volatile Map<String, String> machineByFingerprint = Map.of();

    public Pins() {
    }

    /**
     * Replaces the pinned fingerprints with a copy of {@code machineByFingerprint}.
     *
     * @throws IllegalArgumentException when a key is not a fingerprint; nothing is replaced then
     */
    public void set(final Map<String, String> machineByFingerprint) {
        final Map<String, String> copy = Map.copyOf(machineByFingerprint);
        for (final String f : copy.keySet()) {
            if (!MachinesPort.FINGERPRINT.matcher(f).matches()) {
                throw new IllegalArgumentException("not a fingerprint [fingerprint=" + f + ']');
            }
        }
        this.machineByFingerprint = copy;
    }

    /** The machine whose key {@code peer} carries, or {@code null} when that key is not pinned. */
    public String machineOf(final Certificate peer) {
        Objects.requireNonNull(peer, "peer");
        return machineByFingerprint.get(MachineKey.fingerprint(peer.getPublicKey()));
    }
}
