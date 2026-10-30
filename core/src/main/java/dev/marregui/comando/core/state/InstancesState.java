package dev.marregui.comando.core.state;

import java.util.List;
import java.util.Objects;

/**
 * The comando instances this one sees on the comando Edge, and the questions between them (record 124): what the
 * sidebar's Instances tab shows and {@code ui_get_state} lists. The peers' part comes from their retained presence.
 *
 * @param connected whether this instance is connected to the comando Edge now
 * @param self      this instance, as its own presence says it
 * @param others    the other instances present, by name
 * @param consults  the questions this instance asked and answered since it started, oldest first, at most
 *                  {@link #CONSULTS}
 * @param denied    the instances whose questions this one refuses (the data root's {@code agents.json} peers.deny)
 */
public record InstancesState(boolean connected, Instance self, List<Instance> others, List<Consult> consults, List<String> denied) {

    /** Questions kept for the tab. */
    public static final int CONSULTS = 50;

    /** Before the instances are first read. */
    public static final InstancesState NONE = new InstancesState(false, Instance.NONE, List.of(), List.of(), List.of());

    public InstancesState {
        Objects.requireNonNull(self, "self");
        others = List.copyOf(others);
        consults = List.copyOf(consults);
        denied = List.copyOf(denied);
    }

    /**
     * An instance, as its presence says.
     *
     * @param role    what the instance is for, from its data root's {@code instance.json}; empty when unset
     * @param kit     its kit's commit, short; empty when the kit is not a git checkout
     * @param started when it started, ISO-8601
     */
    public record Instance(String slug, String name, String version, String role, String kit, boolean busy, String started,
                           List<Member> sessions) {

        public static final Instance NONE = new Instance("", "", "", "", "", false, "", List.of());

        public Instance {
            slug = slug == null ? "" : slug;
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            role = role == null ? "" : role;
            kit = kit == null ? "" : kit;
            started = started == null ? "" : started;
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
        }
    }

    /** One of an instance's sessions. */
    public record Member(String name, boolean busy) {
    }

    /** Who put the question: this instance to a peer, or a peer to this instance. */
    public enum Direction { ASKED, ANSWERED }

    /** Where a question stands. */
    public enum Status { WAITING, OK, REFUSED, TIMEOUT, ERROR }

    /**
     * A question between this instance and a peer.
     *
     * @param session  the session that asked it here (empty: the user, from the tab), or the session that answers it here
     * @param askedAt  epoch milliseconds
     * @param endedAt  epoch milliseconds, or 0 while it waits
     * @param reply    the reply's text, clipped; empty while it waits
     */
    public record Consult(String id, Direction direction, String peer, String peerName, String session, String question, Status status,
                          long askedAt, long endedAt, String reply) {

        public Consult {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(status, "status");
            peer = peer == null ? "" : peer;
            peerName = peerName == null ? "" : peerName;
            session = session == null ? "" : session;
            question = question == null ? "" : question;
            reply = reply == null ? "" : reply;
        }

        public Consult ended(Status s, String text, long at) {
            return new Consult(id, direction, peer, peerName, session, question, s, askedAt, at, text);
        }
    }
}
