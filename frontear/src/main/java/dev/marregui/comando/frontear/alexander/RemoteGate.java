package dev.marregui.comando.frontear.alexander;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.frontear.gateway.Gate;
import dev.marregui.comando.frontear.protocol.Packet;

/**
 * What a connection of another machine may do (ADR 0189 §2.1), and for a client of this machine what {@code local}
 * says. A client of machine {@code m} connects only as a link, client id {@code <slug>~<node>}, its slug free of
 * {@code ~} and {@code @}, its node bare or {@code <name>@<this machine>} (whether that node is this one is the
 * broker's to check); it publishes only to {@code comando/v1/m/instance/<slug>/presence} and to
 * {@code comando/v1/<this machine>/instance/<slug>/inbox} or {@code .../reply}, each level matched exactly and the
 * instance's slug one level; it subscribes to nothing. So a remote machine announces only its own instances and asks
 * and answers only this machine's, and nothing under {@code $frontear/} is open to it.
 * <p>
 * A remote machine has the broker retain at most {@link #MAX_PRESENCES} presences at once, distinct topics whose
 * retained message neither was cleared by an empty one nor passed its message expiry; a retained presence past them
 * is refused and counted, so a machine cannot fill the broker's retained store with made-up instances.
 * <p>
 * The topic layout parsed here is the one the instances' peers build ({@code dev.marregui.comando.mcp.Peers}, a
 * module frontear does not see): a change to one is a change to both.
 */
public final class RemoteGate implements Gate {

    private static final System.Logger LOG = System.getLogger(RemoteGate.class.getName());

    private static final char LINK_MARK = '~';
    private static final String ROOT = "comando";
    private static final String VERSION = "v1";
    private static final String INSTANCE = "instance";
    private static final String PRESENCE = "presence";
    private static final String INBOX = "inbox";
    private static final String REPLY = "reply";
    // comando/v1/<machine>/instance/<slug>/<kind>
    private static final int LEVELS = 6;
    /** The retained presences one remote machine may hold at once: the instances one machine runs, with room. */
    static final int MAX_PRESENCES = 64;

    private final Gate local;
    private final String machine;
    private final LongSupplier nanos;
    // per remote machine, the presence topics retained for it now and when each was retained; guarded by itself
    private final Map<String, Map<String, Held>> presences = new HashMap<>();
    // the remote machines refused a presence since they last had room, each logged once a time; guarded by presences
    private final Set<String> full = new HashSet<>();
    private final LongAdder refusedPresences = new LongAdder();

    /** A gate for this machine, {@code machine}, passing its own clients to {@code local}. */
    public RemoteGate(final Gate local, final String machine) {
        this(local, machine, System::nanoTime);
    }

    /** As above, a retained presence's expiry read on {@code nanos}. */
    @TestOnly
    RemoteGate(final Gate local, final String machine, final LongSupplier nanos) {
        this.local = Objects.requireNonNull(local, "local");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        if (machine.isEmpty()) {
            throw new IllegalArgumentException("machine id is empty");
        }
    }

    @Override
    public boolean mayPublish(final String clientId, final String topic) {
        return local.mayPublish(clientId, topic);
    }

    @Override
    public boolean maySubscribe(final String clientId, final String filter) {
        return local.maySubscribe(clientId, filter);
    }

    @Override
    public boolean mayConnect(final String principal, final String clientId) {
        if (principal == null) {
            return local.mayConnect(null, clientId);
        }
        final int mark = clientId.indexOf(LINK_MARK);
        if (mark <= 0 || mark != clientId.lastIndexOf(LINK_MARK)
                || !InstanceName.unmarked(clientId.substring(0, mark))) {
            return false;
        }
        final String aimed = clientId.substring(mark + 1);
        final String on = InstanceName.machineOf(aimed);
        return on.isEmpty() ? !aimed.isEmpty() && InstanceName.unmarked(aimed)
                : on.equals(machine) && InstanceName.unmarked(InstanceName.slugOf(aimed));
    }

    @Override
    public boolean mayPublish(final String principal, final String clientId, final String topic) {
        if (principal == null) {
            return local.mayPublish(null, clientId, topic);
        }
        final String[] levels = topic.split("/", -1);
        if (levels.length != LEVELS || !levels[0].equals(ROOT) || !levels[1].equals(VERSION)
                || !levels[3].equals(INSTANCE) || !oneName(levels[4])) {
            return false;
        }
        return switch (levels[5]) {
            case PRESENCE -> levels[2].equals(principal);
            case INBOX, REPLY -> levels[2].equals(machine);
            default -> false;
        };
    }

    @Override
    public boolean maySubscribe(final String principal, final String clientId, final String filter) {
        return principal == null && local.maySubscribe(null, clientId, filter);
    }

    /**
     * A client of this machine retains as the local gate says. A remote machine's retained presence on a topic it
     * holds none on is refused once it holds {@link #MAX_PRESENCES}; an empty one clears its topic and is never
     * refused. Any other topic a remote machine may publish to is retained as the broker's bounds allow.
     */
    @Override
    public boolean mayRetain(final String principal, final String clientId, final Packet.Publish message) {
        if (principal == null) {
            return local.mayRetain(null, clientId, message);
        }
        final String topic = message.topic();
        if (!topic.endsWith('/' + PRESENCE)) {
            return true;
        }
        final long now = nanos.getAsLong();
        final boolean logged;
        final int held;
        synchronized (presences) {
            final Map<String, Held> of = presences.computeIfAbsent(principal, _ -> new HashMap<>());
            if (message.payload().length == 0) {
                of.remove(topic);
                full.remove(principal);
                return true;
            }
            of.values().removeIf(h -> h.expired(now));
            if (of.size() < MAX_PRESENCES || of.containsKey(topic)) {
                of.put(topic, new Held(now, message.meta().messageExpirySeconds()));
                full.remove(principal);
                return true;
            }
            refusedPresences.increment();
            logged = full.add(principal);
            held = of.size();
        }
        if (logged) {
            LOG.log(System.Logger.Level.WARNING, "retained presence refused, the machine holds its most, the next "
                    + "refused not logged until it has room [machine=" + principal + ", topic=" + topic + ", held="
                    + held + ", maxPresences=" + MAX_PRESENCES + ']');
        }
        return false;
    }

    /** The retained presences refused to remote machines since the gate was made. */
    @TestOnly
    long refusedPresences() {
        return refusedPresences.sum();
    }

    /** Whether {@code level} names one instance: not empty, and no wildcard, which a topic name cannot hold anyway. */
    private static boolean oneName(final String level) {
        return !level.isEmpty() && level.indexOf('+') < 0 && level.indexOf('#') < 0;
    }

    /** A retained presence: when the gate let it in, and its message expiry, -1 for none, as the broker keeps it. */
    private record Held(long sinceNanos, long expirySeconds) {

        boolean expired(final long nowNanos) {
            return expirySeconds >= 0 && nowNanos - sinceNanos >= TimeUnit.SECONDS.toNanos(expirySeconds);
        }
    }
}
