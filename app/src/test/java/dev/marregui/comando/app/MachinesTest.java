package dev.marregui.comando.app;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.port.MachinesPort.Grant;
import dev.marregui.comando.core.port.MachinesPort.Paired;
import dev.marregui.comando.core.port.MachinesPort.Self;
import dev.marregui.comando.core.port.MachinesPort.Token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Machines]: this machine and the machines paired with it (record 189), each test on two machines `a` and `b` whose
/// folders and remotes files are in a temporary folder, never the user's `~/.comando-machine/` or
/// `~/.comando-remotes.json`, and whose listen address is the loopback's, so nothing leaves this machine.
/// - a machine is named at the first ask, from its host's name, in an owner-only machine file of version 1
/// - a token reads back as the pairing it names, an IPv6 host whole; a token that does not read is refused, and so is
///   this machine's own, by its id or by its key
/// - the remotes file is owner-only and versioned; one of a later version is read by none and written by none
/// - a listen address is an IP literal of this machine, never the wildcard; none empties the token
/// - unpairing removes the pairing; a grant reads from the pairings; the gateway's link state shows per machine
class MachinesTest {

    /// The port each machine file names here; nothing listens on it, as no test opens a listener.
    private static final int PORT_A = 40_001;
    private static final int PORT_B = 40_002;
    private static final String LOOPBACK = "127.0.0.1";

    @TempDir
    Path dir;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
    private Machines a;
    private Machines b;

    @BeforeEach
    void setUp() {
        a = machine("a", PORT_A);
        b = machine("b", PORT_B);
    }

    /// The first ask names the machine from its default id and writes its file, owner-only in an owner-only folder,
    /// version 1, no listen address, its port; its token is empty while it listens nowhere. A host's name reads as an
    /// id: lowercase, other runs a dash, at most 32 characters, `machine` when nothing is left.
    @Test
    void theMachineIsNamedAtTheFirstAskInAnOwnerOnlyFile() throws Exception {
        final Self me = a.self();

        assertThat(me.machine()).isEqualTo("a");
        assertThat(me.host()).isEmpty();
        assertThat(me.port()).isEqualTo(PORT_A);
        assertThat(me.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(me.token()).as("nothing reaches a machine that listens nowhere").isEmpty();
        final Path file = dir.resolve("a-machine/machine.json");
        final JsonNode tree = Json.readTree(Files.readString(file));
        assertThat(tree.path("version").asInt(0)).isEqualTo(Machines.VERSION);
        assertThat(tree.path("machine").asString("")).isEqualTo("a");
        assertThat(tree.path("host").asString("x")).isEmpty();
        assertThat(tree.path("port").asInt(0)).isEqualTo(PORT_A);
        assertThat(mode(file)).isEqualTo("rw-------");
        assertThat(mode(dir.resolve("a-machine"))).isEqualTo("rwx------");
        assertThat(new Machines(dir.resolve("a-machine"), dir.resolve("a-remotes.json"), PORT_B, () -> "other",
                now::get).self()).as("a machine file written stays the machine's")
                .extracting(Self::machine, Self::port).containsExactly("a", PORT_A);
        assertThat(Machines.id(" My Mac.local ")).isEqualTo("my-mac-local");
        assertThat(Machines.id("-".repeat(3) + "x".repeat(40))).isEqualTo("x".repeat(32));
        assertThat(Machines.id("x".repeat(31) + ".y")).as("the cut ends on no dash").isEqualTo("x".repeat(31));
        assertThat(Machines.id("!!")).isEqualTo("machine");
    }

    /// A machine that listens on the loopback has the token `comando-pair:1:<machine>:<host>:<port>:<fingerprint>`;
    /// the other machine reads it back as that pairing, the grant it is given or peer by default, and pairing it keeps
    /// it so, not connected and never connected. An IPv6 host reads whole between the machine and the port.
    @Test
    void aTokenReadsBackAsThePairingItNames() {
        b.listen(LOOPBACK);
        final Self other = b.self();

        assertThat(other.token()).isEqualTo("comando-pair:1:b:" + LOOPBACK + ':' + PORT_B + ':' + other.fingerprint());
        assertThat(Machines.parse(other.token(), null))
                .isEqualTo(new Machines.Remote("b", LOOPBACK, PORT_B, other.fingerprint(), Grant.PEER));
        assertThat(a.pair(other.token(), Grant.OVERSEER))
                .isEqualTo(new Paired("b", LOOPBACK, PORT_B, other.fingerprint(), Grant.OVERSEER, false, 0L));
        assertThat(a.paired()).containsExactly(
                new Paired("b", LOOPBACK, PORT_B, other.fingerprint(), Grant.OVERSEER, false, 0L));
        assertThat(Machines.parse(new Token("c", "fe80::1%en0", 1, other.fingerprint()).text(), Grant.PEER).host())
                .isEqualTo("fe80::1%en0");
    }

    /// A token not of this kind or version, with too few parts, or whose machine, host, port or fingerprint does not
    /// read, is refused, and nothing is paired.
    @Test
    void aTokenThatDoesNotReadIsRefused() {
        final String fp = "0123456789abcdef".repeat(4);
        final List<String> bad = List.of(
                "",
                "comando-pair:2:b:" + LOOPBACK + ":1:" + fp,
                "comando-pair:1:b",
                "comando-pair:1:b:" + fp,
                "comando-pair:1:B_x:" + LOOPBACK + ":1:" + fp,
                "comando-pair:1:b::1:" + fp,
                "comando-pair:1:b:" + LOOPBACK + ":port:" + fp,
                "comando-pair:1:b:" + LOOPBACK + ":0:" + fp,
                "comando-pair:1:b:" + LOOPBACK + ":65536:" + fp,
                "comando-pair:1:b:" + LOOPBACK + ":1:" + fp.substring(1),
                "comando-pair:1:b:" + LOOPBACK + ":1:" + fp.toUpperCase(Locale.ROOT));

        for (final String token : bad) {
            assertThatThrownBy(() -> a.pair(token, Grant.PEER)).as(token).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Machines.parse(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.paired()).isEmpty();
        assertThat(dir.resolve("a-remotes.json")).doesNotExist();
    }

    /// This machine's own token is refused, by its id, and so is another id that holds this machine's key.
    @Test
    void thisMachinesOwnTokenIsRefused() {
        a.listen(LOOPBACK);
        final Self me = a.self();

        assertThatThrownBy(() -> a.pair(me.token(), Grant.PEER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.pair(new Token("c", LOOPBACK, PORT_B, me.fingerprint()).text(), Grant.PEER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(a.paired()).isEmpty();
        assertThat(dir.resolve("a-remotes.json")).doesNotExist();
    }

    /// The remotes file is owner-only, one found readable by others too narrowed at the next write, of version 1, and
    /// holds each pairing's host, port, fingerprint and grant by machine id; pairing a machine again replaces its
    /// pairing.
    @Test
    void theRemotesFileIsOwnerOnlyAndVersioned() throws Exception {
        final Path file = dir.resolve("a-remotes.json");
        Files.writeString(file, "{\"version\":1,\"machines\":{}}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        b.listen(LOOPBACK);
        a.pair(b.self().token(), Grant.PEER);
        a.pair(b.self().token(), Grant.OVERSEER);

        assertThat(mode(file)).isEqualTo("rw-------");
        final JsonNode tree = Json.readTree(Files.readString(file));
        assertThat(tree.path("version").asInt(0)).isEqualTo(Machines.VERSION);
        final JsonNode pairing = tree.path("machines").path("b");
        assertThat(pairing.path("host").asString("")).isEqualTo(LOOPBACK);
        assertThat(pairing.path("port").asInt(0)).isEqualTo(PORT_B);
        assertThat(pairing.path("fingerprint").asString("")).isEqualTo(b.self().fingerprint());
        assertThat(pairing.path("grant").asString("")).isEqualTo("overseer");
        assertThat(tree.path("machines").properties()).hasSize(1);
    }

    /// A grant in the remotes file reads in any case, as the `machines` tool reads it; one that names no grant reads
    /// as peer, the least the pairing allows, and none reads as peer too.
    @Test
    void aGrantInTheRemotesFileReadsInAnyCaseAndAnUnknownOneAsPeer() throws Exception {
        final String pairing = "{\"host\":\"127.0.0.1\",\"port\":1,\"fingerprint\":\"" + "0123456789abcdef".repeat(4)
                + '"';
        Files.writeString(dir.resolve("a-remotes.json"), "{\"version\":1,\"machines\":{"
                + "\"b\":" + pairing + ",\"grant\":\"Overseer\"},"
                + "\"c\":" + pairing + ",\"grant\":\"oversear\"},"
                + "\"d\":" + pairing + "}}}");

        assertThat(a.grantOf("b")).isEqualTo(Grant.OVERSEER);
        assertThat(a.grantOf("c")).isEqualTo(Grant.PEER);
        assertThat(a.grantOf("d")).isEqualTo(Grant.PEER);
    }

    /// A remotes file of a later version is read by none, so nothing is paired, and written by none, so a pairing is
    /// refused and the file stays as it was.
    @Test
    void aRemotesFileOfALaterVersionIsReadByNone() throws Exception {
        b.listen(LOOPBACK);
        final Path file = dir.resolve("a-remotes.json");
        final byte[] later = ("{\"version\":" + (Machines.VERSION + 1) + ",\"machines\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, later);

        assertThat(a.paired()).isEmpty();
        assertThat(a.grantOf("b")).isNull();
        assertThatThrownBy(() -> a.pair(b.self().token(), Grant.PEER)).isInstanceOf(UncheckedIOException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(later);
    }

    /// The listen address is refused when it is the wildcard, of either family, an address not of this machine (one
    /// of TEST-NET-1, RFC 5737, which no host is given) or not an IP literal; the loopback's is taken and names the
    /// token's host; an empty one stops listening and empties the token.
    @Test
    void listenRefusesTheWildcardAndAnAddressNotOfThisMachine() {
        for (final String host : List.of("0.0.0.0", "::", "192.0.2.1", "localhost")) {
            assertThatThrownBy(() -> a.listen(host)).as(host).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(a.self().host()).isEmpty();

        a.listen(LOOPBACK);
        assertThat(a.self().host()).isEqualTo(LOOPBACK);
        assertThat(a.self().token()).contains(':' + LOOPBACK + ':');

        a.listen("");
        assertThat(a.self().host()).isEmpty();
        assertThat(a.self().token()).isEmpty();
    }

    /// Unpairing removes the pairing from the file and its grant, and says whether there was one.
    @Test
    void unpairRemovesThePairing() {
        b.listen(LOOPBACK);
        a.pair(b.self().token(), Grant.OVERSEER);
        assertThat(a.grantOf("b")).isEqualTo(Grant.OVERSEER);

        assertThat(a.unpair("b")).isTrue();
        assertThat(a.paired()).isEmpty();
        assertThat(a.grantOf("b")).as("not paired").isNull();
        assertThat(a.unpair("b")).isFalse();
    }

    /// A grant reads from the pairings another instance of this machine wrote, once read: null for a machine not
    /// paired, the grant written otherwise.
    @Test
    void aGrantReadsFromThePairings() throws Exception {
        b.listen(LOOPBACK);
        final Machines sameMachine = machine("a", PORT_A);
        assertThat(sameMachine.grantOf("b")).isNull();

        a.pair(b.self().token(), Grant.OVERSEER);
        sameMachine.remotes();

        assertThat(sameMachine.grantOf("b")).isEqualTo(Grant.OVERSEER);
        assertThat(sameMachine.grantOf("c")).isNull();
    }

    /// The gateway's link state shows per paired machine: connected while its last read said so, with when it last
    /// was; a machine its read does not name is not connected and keeps when it last was.
    @Test
    void theGatewaysLinkStateShowsPerMachine() {
        b.listen(LOOPBACK);
        a.pair(b.self().token(), Grant.PEER);
        final Instant up = now.get();

        a.connected(Map.of("b", true));
        assertThat(a.paired()).singleElement().satisfies(p -> {
            assertThat(p.connected()).isTrue();
            assertThat(p.lastConnectedMillis()).isEqualTo(up.toEpochMilli());
        });

        now.set(up.plusSeconds(60));
        a.connected(Map.of("b", false));
        assertThat(a.paired()).singleElement().satisfies(p -> {
            assertThat(p.connected()).isFalse();
            assertThat(p.lastConnectedMillis()).as("when it last was").isEqualTo(up.toEpochMilli());
        });

        a.connected(Map.of("b", true));
        a.connected(Map.of());
        assertThat(a.paired()).singleElement().extracting(Paired::connected).isEqualTo(false);
    }

    /// The machine `id` with its folder and remotes file in the temporary folder, its port `port`.
    private Machines machine(final String id, final int port) {
        return new Machines(dir.resolve(id + "-machine"), dir.resolve(id + "-remotes.json"), port, () -> id,
                now::get);
    }

    private static String mode(final Path path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
