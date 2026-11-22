package dev.marregui.comando.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// The `machines` tool (record 189): this machine and its pairings through a port in memory. The views ask nothing of
/// the user; listening on an address and pairing wait for the user's yes on a connection's card, which an agent cannot
/// give, and change nothing when denied; unpairing needs no card.
class MachinesToolsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String TOKEN = "comando-pair:1:b:10.0.0.2:7443:ab12cd";

    private final RecordingSink sink = new RecordingSink();
    private final FakeMachines port = new FakeMachines();
    private McpTools tools;

    @BeforeEach
    void start() {
        tools = new McpTools(sink, States::twoPanels, new TranscriptRegistry(), null, AuditLog.none(), new FakeClock(),
                Duration.ofMinutes(1));
        tools.machines(port);
    }

    /// The tool is listed; with no port set it answers this machine unnamed and nothing paired.
    @Test
    void itIsListedAndAnswersAnUnnamedMachineBeforeThePortIsSet() throws Exception {
        assertThat(tools.builtIn()).contains("machines");
        final McpTools bare = new McpTools(sink, States::twoPanels, new TranscriptRegistry(), null, AuditLog.none(),
                new FakeClock(), Duration.ofMinutes(1));
        final JsonNode self = json(bare.call("work", "machines", Json.readTree("{}")));
        assertThat(self.path("machine").asString()).isEmpty();
        assertThat(self.path("dropped").asLong()).isZero();
        assertThat(json(bare.call("work", "machines", Json.readTree("{\"view\":\"list\"}"))).path("machines").size())
                .isZero();
    }

    /// `self` answers the machine, its address, port, fingerprint and token; `list` each paired machine with its
    /// grant and link; neither puts a card.
    @Test
    void theViewsAnswerWithoutACard() throws Exception {
        port.paired.add(new MachinesPort.Paired("c", "10.0.0.3", 7443, "ff00", MachinesPort.Grant.OVERSEER, true, 5L));
        final JsonNode self = json(call("{}"));
        assertThat(List.of(self.path("machine").asString(), self.path("host").asString(), self.path("port").asInt(),
                self.path("fingerprint").asString(), self.path("token").asString()))
                .containsExactly("a", "10.0.0.1", 7443, "aa11", "comando-pair:1:a:10.0.0.1:7443:aa11");
        final JsonNode c = json(call("{\"view\":\"list\"}")).path("machines").get(0);
        assertThat(List.of(c.path("machine").asString(), c.path("grant").asString(), c.path("connected").asBoolean(),
                c.path("lastConnectedMillis").asLong())).containsExactly("c", "overseer", true, 5L);
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    /// A pairing puts a connection's card naming the grant and the token's fingerprint, and pairs nothing while it
    /// waits; denied, nothing is paired; allowed, the machine is paired with the grant asked, `peer` when none.
    @Test
    void aPairingWaitsForTheUsersCardAndADenialPairsNothing() throws Exception {
        final CompletableFuture<McpTools.Outcome> denied = async("{\"action\":\"pair\",\"token\":\"" + TOKEN
                + "\",\"grant\":\"overseer\"}");
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.CONNECTION);
        assertThat(asked.approval().summary()).contains("pair it as overseer", "ab12cd");
        assertThat(port.pairs).as("nothing while the card waits").isEmpty();
        tools.resolve(asked.approval(), new ApprovalDecision.Deny("no"));
        assertThat(denied.get(WAIT.toSeconds(), TimeUnit.SECONDS).error()).isTrue();
        assertThat(port.pairs).as("denied").isEmpty();

        final CompletableFuture<McpTools.Outcome> allowed = async("{\"action\":\"pair\",\"token\":\"" + TOKEN + "\"}");
        sink.await(Intent.ApprovalRequested.class, _ -> sink.of(Intent.ApprovalRequested.class).size() == 2, WAIT);
        final Intent.ApprovalRequested again = sink.of(Intent.ApprovalRequested.class).get(1);
        assertThat(again.approval().summary()).contains("pair it as peer", "plan mode");
        tools.resolve(again.approval(), ApprovalDecision.allowOnce());
        final McpTools.Outcome out = allowed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(port.pairs).containsExactly(TOKEN + " PEER");
        assertThat(json(out).path("paired").path("grant").asString()).isEqualTo("peer");
    }

    /// Listening on an address puts a card naming it; allowed, the port is told and the answer is this machine.
    @Test
    void listeningOnAnAddressWaitsForTheUsersCard() throws Exception {
        final CompletableFuture<McpTools.Outcome> listen = async("{\"action\":\"listen\",\"host\":\"10.0.0.9\"}");
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().summary()).contains("listen on 10.0.0.9:7443");
        assertThat(port.listened).isEmpty();
        tools.resolve(asked.approval(), ApprovalDecision.allowOnce());
        assertThat(json(listen.get(WAIT.toSeconds(), TimeUnit.SECONDS)).path("host").asString()).isEqualTo("10.0.0.9");
        assertThat(port.listened).containsExactly("10.0.0.9");
    }

    /// Unpairing and a listen that stops close this machine, so they need no card; the answer says whether the machine
    /// was paired.
    @Test
    void closingNeedsNoCard() throws Exception {
        port.paired.add(new MachinesPort.Paired("c", "10.0.0.3", 7443, "ff00", MachinesPort.Grant.PEER, false, 0L));
        final JsonNode out = json(call("{\"action\":\"unpair\",\"machine\":\"c\"}"));
        assertThat(out.path("unpaired").asBoolean()).isTrue();
        assertThat(json(call("{\"action\":\"unpair\",\"machine\":\"c\"}")).path("unpaired").asBoolean()).isFalse();
        assertThat(json(call("{\"action\":\"listen\",\"host\":\"\"}")).path("host").asString()).isEmpty();
        assertThat(port.listened).containsExactly("");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    /// A call that cannot run is refused before any card: an unknown action, a pairing without a token or with an
    /// unknown grant, an unpairing without a machine; the port's own refusal comes back as the tool's error.
    @Test
    void whatCannotRunIsRefusedBeforeAnyCard() throws Exception {
        assertThat(call("{\"action\":\"wipe\"}").text()).contains("unknown view or action [name=wipe");
        assertThat(call("{\"action\":\"pair\"}").text()).contains("pair needs the other machine's token");
        assertThat(call("{\"action\":\"pair\",\"token\":\"t\",\"grant\":\"root\"}").text())
                .contains("unknown grant [grant=root");
        assertThat(call("{\"action\":\"unpair\"}").text()).contains("unpair needs the machine's id");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();

        port.refuse = true;
        final CompletableFuture<McpTools.Outcome> bad = async("{\"action\":\"pair\",\"token\":\"x\"}");
        tools.resolve(sink.await(Intent.ApprovalRequested.class, WAIT).approval(), ApprovalDecision.allowOnce());
        final McpTools.Outcome out = bad.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("the token does not read");
    }

    private McpTools.Outcome call(final String args) throws Exception {
        return tools.call("work", "machines", Json.readTree(args));
    }

    private CompletableFuture<McpTools.Outcome> async(final String args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call(args);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static JsonNode json(final McpTools.Outcome out) {
        assertThat(out.error()).as(out.text()).isFalse();
        return Json.readTree(out.text());
    }

    /// This machine `a` and its pairings in memory, recording what it was told.
    private static final class FakeMachines implements MachinesPort {
        final List<Paired> paired = new ArrayList<>();
        final List<String> pairs = new ArrayList<>();
        final List<String> listened = new ArrayList<>();
        /// Whether `pair` refuses the token: set by the test's thread, read by the thread that runs the tool call.
        volatile boolean refuse;
        private String host = "10.0.0.1";

        @Override
        public synchronized Self self() {
            return new Self("a", host, 7443, "aa11", "comando-pair:1:a:" + host + ":7443:aa11");
        }

        @Override
        public synchronized List<Paired> paired() {
            return List.copyOf(paired);
        }

        @Override
        public synchronized void listen(final String to) {
            listened.add(to);
            host = to;
        }

        @Override
        public synchronized Paired pair(final String token, final Grant grant) {
            if (refuse) {
                throw new IllegalArgumentException("the token does not read [token=" + token + ']');
            }
            pairs.add(token + ' ' + grant);
            final Paired p = new Paired("b", "10.0.0.2", 7443, "ab12cd", grant, false, 0L);
            paired.add(p);
            return p;
        }

        @Override
        public synchronized boolean unpair(final String machine) {
            return paired.removeIf(p -> p.machine().equals(machine));
        }
    }
}
