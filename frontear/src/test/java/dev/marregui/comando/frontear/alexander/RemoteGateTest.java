package dev.marregui.comando.frontear.alexander;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// What a [RemoteGate] for machine `here` lets a connection of machine `there` do, and that a client of this machine
/// (no principal) gets what the local gate, a [ControlGate], says:
/// - a remote client connects only as a link, `<slug>~<node>`, its node bare or `<name>@here`
/// - it publishes only `comando/v1/there/instance/<slug>/presence` and `comando/v1/here/instance/<slug>/inbox|reply`
/// - it subscribes to nothing
/// - it has at most 64 presences retained at once
class RemoteGateTest {

    private final RemoteGate gate = new RemoteGate(new ControlGate(), "here");

    /// A link client id, its node bare or this machine's, is let in.
    @Test
    void aRemoteLinkMayConnect() {
        for (final String id : List.of("a~main", "main~main@here", "a.b-c~n1", "a~main@here")) {
            assertThat(gate.mayConnect("there", id)).as(id).isTrue();
        }
    }

    /// No `~`, a second one, an empty slug or node, a machine in the slug, or a node of another machine are refused.
    @Test
    void aRemoteClientThatIsNoLinkToThisMachineMayNotConnect() {
        for (final String id : List.of("a", "", "~main", "a~", "a~b~main", "a@there~main", "@~main", "a~main@",
                "a~@here", "a~main@there", "a~main@here@here", "a~main@herex")) {
            assertThat(gate.mayConnect("there", id)).as(id).isFalse();
        }
    }

    /// A client of this machine connects as the local gate says, any id.
    @Test
    void aLocalClientConnectsAsTheLocalGateSays() {
        assertThat(gate.mayConnect(null, "pod")).isTrue();
        assertThat(gate.mayConnect(null, "a~main")).isTrue();
    }

    /// The remote machine's own presence and this machine's inbox and reply topics are open to it.
    @Test
    void aRemoteClientMayAnnounceItsOwnAndAskAndAnswerThisMachine() {
        for (final String topic : List.of("comando/v1/there/instance/x/presence", "comando/v1/here/instance/x/inbox",
                "comando/v1/here/instance/x/reply")) {
            assertThat(gate.mayPublish("there", "a@there~main", topic)).as(topic).isTrue();
        }
    }

    /// Every other topic is refused: this machine's or a third machine's presence, another machine's inbox, control,
    /// a level more or less, an empty or wildcard slug, a look-alike of any level.
    @Test
    void aRemoteClientMayPublishNothingElse() {
        for (final String topic : List.of("comando/v1/here/instance/x/presence",
                "comando/v1/other/instance/x/presence", "comando/v1/there/instance/x/inbox",
                "comando/v1/there/instance/x/reply", "$frontear/control/main/PutLink", "$frontear/presence/main",
                "comando/v1/here/instance/x/inbox/more", "comando/v1/here/instance/inbox",
                "comando/v1/here/instance//inbox", "comando/v1/here/instance/+/inbox",
                "comando/v1/here/instance/#/inbox", "comando/v2/here/instance/x/inbox",
                "comandos/v1/here/instance/x/inbox", "comando/v1/here/instances/x/inbox",
                "comando/v1/here/instance/x/inboxes", "comando/v1/here/instance/x/", "a/b", "")) {
            assertThat(gate.mayPublish("there", "a@there~main", topic)).as(topic).isFalse();
        }
    }

    /// A remote client may subscribe to nothing, even what a local client may; a local client subscribes as the local
    /// gate says.
    @Test
    void aRemoteClientMaySubscribeToNothing() {
        for (final String filter : List.of("#", "comando/v1/here/instance/x/inbox",
                "comando/v1/there/instance/+/presence", "a/b")) {
            assertThat(gate.maySubscribe("there", "a@there~main", filter)).as(filter).isFalse();
            assertThat(gate.maySubscribe(null, "pod", filter)).as(filter).isTrue();
        }
    }

    /// A client of this machine publishes as the local gate says, under both forms of the question.
    @Test
    void aLocalClientPublishesAsTheLocalGateSays() {
        assertThat(gate.mayPublish(null, "pod", "comando/v1/other/instance/x/presence")).isTrue();
        assertThat(gate.mayPublish("pod", "a/b")).isTrue();
        assertThat(gate.mayPublish(null, "pod", "$frontear/presence/main")).isFalse();
        assertThat(gate.mayPublish("pod", "$frontear/state/main/links")).isFalse();
        assertThat(gate.maySubscribe("pod", "$frontear/state/#")).isTrue();
    }

    /// A remote machine has 64 presences retained at once: the 65th distinct one is refused and counted, a presence
    /// already held is retained again, and a clear or an expiry makes room; a client of this machine and another
    /// remote machine are not held to it.
    @Test
    void aRemoteMachineHoldsAtMost64RetainedPresences() {
        final AtomicLong now = new AtomicLong();
        final RemoteGate held = new RemoteGate(new ControlGate(), "here", now::get);
        for (int i = 0; i < RemoteGate.MAX_PRESENCES; i++) {
            assertThat(held.mayRetain("there", "a~main", presence("there", "i" + i, 30))).as("i" + i).isTrue();
        }
        assertThat(held.mayRetain("there", "a~main", presence("there", "x", 30))).isFalse();
        assertThat(held.mayRetain("there", "a~main", presence("there", "i0", 30))).isTrue();
        assertThat(held.mayRetain("other", "a~main", presence("other", "x", 30))).isTrue();
        assertThat(held.mayRetain(null, "pod", presence("there", "x", 30))).isTrue();
        assertThat(held.refusedPresences()).isEqualTo(1);

        assertThat(held.mayRetain("there", "a~main", clear("there", "i1"))).isTrue();
        assertThat(held.mayRetain("there", "a~main", presence("there", "x", 30))).isTrue();
        assertThat(held.mayRetain("there", "a~main", presence("there", "y", 30))).isFalse();

        now.addAndGet(TimeUnit.SECONDS.toNanos(30));
        assertThat(held.mayRetain("there", "a~main", presence("there", "y", -1))).isTrue();
        assertThat(held.refusedPresences()).isEqualTo(2);
    }

    /// A gate needs this machine's id.
    @Test
    void aGateNeedsThisMachine() {
        assertThatThrownBy(() -> new RemoteGate(new ControlGate(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteGate(new ControlGate(), null)).isInstanceOf(NullPointerException.class);
    }

    private static Packet.Publish presence(final String machine, final String slug, final long expirySeconds) {
        return new Packet.Publish("comando/v1/" + machine + "/instance/" + slug + "/presence", new byte[] {'{', '}'},
                1, true, false, 1, null, null, new Packet.Meta(-1, expirySeconds, null, List.of()));
    }

    private static Packet.Publish clear(final String machine, final String slug) {
        return new Packet.Publish("comando/v1/" + machine + "/instance/" + slug + "/presence", new byte[0], 1, true,
                false, 1, null, null);
    }
}
