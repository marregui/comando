package dev.marregui.comando.core.state;

import java.util.List;
import java.util.Objects;

/**
 * The comando instances this one sees on the comando Edge, and the questions between them (record 124): what the
 * sidebar's Instances tab shows and {@code ui_get_state} lists. The peers' part comes from their retained presence.
 *
 * @param connected whether this instance is connected to the comando Edge now
 * @param self      this instance, as its own presence says it
 * @param others    the other instances, by name: those present, then those seen before and remembered
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
     * An instance, as its presence says, or as this one last saw it.
     *
     * @param role     what the instance is for, from its data root's {@code instance.json}; empty when unset
     * @param kit      its kit's commit, short; empty when the kit is not a git checkout
     * @param started  when it started, ISO-8601
     * @param edge     it started the comando Edge, which it stops
     * @param present  its presence is on the Edge now; false for an instance this one saw before and remembers
     * @param lastSeen when this instance last saw it present, epoch milliseconds; 0 while it is present
     */
    public record Instance(String slug, String name, String version, String role, String kit, boolean busy, String started,
                           List<Member> sessions, boolean edge, boolean present, long lastSeen) {

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

        /** A present instance that did not start the Edge. */
        public Instance(final String slug, final String name, final String version, final String role, final String kit, final boolean busy, final String started, final List<Member> sessions) {
            this(slug, name, version, role, kit, busy, started, sessions, false, true, 0L);
        }
    }

    /**
     * One of an instance's sessions, as its presence says: what it is doing, from the instance's own state and the
     * ends of its conversation, so another instance knows without asking.
     *
     * @param status      its process: {@code OPEN}, {@code CLOSED}, {@code FAILED}…
     * @param activity    what its turn is doing now ("running Bash", "thinking"…); empty between turns
     * @param turnStarted when its running turn started, epoch milliseconds; 0 between turns
     * @param context     how full its context is, in percent; -1 when unknown
     * @param folder      its working folder, the home folder written {@code ~}
     * @param tasks       its background tasks still running, by description
     * @param prompt      the first line of its last prompt, clipped
     * @param reply       the first line of its last reply, clipped
     */
    public record Member(String name, boolean busy, String status, String activity, long turnStarted, String model, String effort,
                         int context, String folder, String branch, List<String> tasks, String prompt, String reply) {

        public Member {
            name = name == null ? "" : name;
            status = status == null ? "" : status;
            activity = activity == null ? "" : activity;
            model = model == null ? "" : model;
            effort = effort == null ? "" : effort;
            folder = folder == null ? "" : folder;
            branch = branch == null ? "" : branch;
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            prompt = prompt == null ? "" : prompt;
            reply = reply == null ? "" : reply;
        }

        /** A session known only by its name and whether it is busy. */
        public Member(final String name, final boolean busy) {
            this(name, busy, "", "", 0L, "", "", -1, "", "", List.of(), "", "");
        }
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

        public Consult ended(final Status s, final String text, final long at) {
            return new Consult(id, direction, peer, peerName, session, question, s, askedAt, at, text);
        }
    }
}
