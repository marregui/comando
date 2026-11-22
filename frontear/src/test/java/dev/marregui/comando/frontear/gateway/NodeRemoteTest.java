package dev.marregui.comando.frontear.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Set;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A node's remote listener, opened through [Frontear#listenRemote]: it installs the gate for other machines over
/// the control plane's, so a paired machine's link connects and publishes to an inbox here but not to control,
/// and [Frontear#closeRemote] frees the port while the node's loopback listener goes on.
class NodeRemoteTest {

    @TempDir
    Path dir;

    /// A link of machine `there` connects to node `main` of machine `here` and publishes to an inbox, is refused
    /// `$frontear/control/...`; after `closeRemote` the port is free and the node still serves its own clients.
    @Test
    void aNodeOpensItsRemoteListenerBehindTheGateForOtherMachines() throws Exception {
        final RemoteTls.Machine here = RemoteTls.make(dir, "here");
        final RemoteTls.Machine there = RemoteTls.make(dir, "there");
        final SSLContext client = RemoteTls.context(there, here);
        try (Frontear node = Frontear.start("main", 0, dir.resolve("data"), Set.of(), InstantSource.system(),
                true)) {
            final int port = node.listenRemote(RemoteTls.listen(RemoteTls.context(here, there),
                    RemoteTls.machineOf(there), "here", 4, RemoteTls.HANDSHAKE));
            try (RawClient remote = RemoteTls.dial(client, port)) {
                remote.send(RawClient.connect("a~main", true, 0));
                assertThat(remote.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.SUCCESS);
                assertThat(remote.publish(RawClient.qos1("comando/v1/here/instance/x/inbox", "q")))
                        .isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
                assertThat(remote.publish(RawClient.qos1(Names.CONTROL + "main/PutLink", "{}")))
                        .isEqualTo(Packet.NOT_AUTHORIZED);
                node.closeRemote();
                assertThat(remote.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SERVER_SHUTTING_DOWN,
                        null));
            }
            node.closeRemote();
            assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), port).close())
                    .isInstanceOf(IOException.class);
            try (RawClient local = RawClient.connected(node.port(), "pod")) {
                assertThat(local.ping()).isEmpty();
            }
        }
    }
}
