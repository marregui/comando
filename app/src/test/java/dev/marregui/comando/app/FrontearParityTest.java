package dev.marregui.comando.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.Processes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// frontear's broker against a real MQTT 5 broker, for the features frontear supports: the HiveMQ Edge release comando
/// pins and a frontear node run side by side on loopback ([ParityBrokers]), frontear's own interop tests run against
/// each, and every scripted scenario of [ParityScenarios] run against both and compared field by field. A difference
/// passes only when [#ALLOWED] names it, as a difference the MQTT 5.0 specification lets a broker choose (the clause
/// cited) and frontear's contract declares, or as a frontear defect, reported with the packets of both brokers and
/// left to its owner; any other difference fails, and so does an allowance whose difference is gone. Opt-in, as the
/// first run downloads the 359 MB release: `COMANDO_PARITY=true`.
final class FrontearParityTest {

    /// Every difference the comparison lets pass, each with its reason; "MQTT n" is a section of the MQTT 5.0
    /// specification, "contract" frontear's broker contract, section 2. A refusal is two differences: the reason
    /// code, and the close that follows it (field `packet`).
    private static final List<Allowance> ALLOWED = List.of(
            declared("connect-capabilities", "a broker may support less than QoS 2 and says so in Maximum QoS "
                    + "(MQTT 3.2.2.3.4), may have no Topic Aliases (MQTT 3.2.2.3.8), Subscription Identifiers "
                    + "(MQTT 3.2.2.3.12) or Shared Subscriptions (MQTT 3.2.2.3.13), and sets its own Receive "
                    + "Maximum (MQTT 3.2.2.3.3) and Maximum Packet Size (MQTT 3.2.2.3.6); frontear's are QoS 1, "
                    + "none, none, none (contract)", "maximumQos", "topicAliasMaximum", "subscriptionIdsAvailable",
                    "sharedAvailable", "receiveMaximum", "maximumPacketSize"),
            declared("connect-user-name", "a server may refuse a CONNECT whose User Name it does not accept, "
                    + "CONNACK 0x86, then closes (MQTT 3.1.4, 3.2.2.2); frontear authenticates by key, never by name "
                    + "(contract)", "reasonCode", "packet"),
            declared("connect-authentication-method", "a server without the Authentication Method asked for may "
                    + "refuse it, CONNACK 0x8C, then closes (MQTT 4.12); frontear has none (contract)", "reasonCode",
                    "packet"),
            declared("connect-will-qos-2", "a will above the server's Maximum QoS is refused, CONNACK 0x9B, then "
                    + "the close (MQTT 3.2.2.3.4); frontear's maximum is 1 (contract)", "reasonCode", "packet"),
            declared("subscribe-granted-qos", "a server may grant a lower QoS than asked (MQTT 3.9.3); frontear "
                    + "grants at most 1 (contract)", "reasonCodes"),
            declared("subscribe-no-local", "a server that knows no subscriber gets a message may answer 0x10 "
                    + "instead of 0x00 (MQTT 3.4.2.1); frontear does when No Local leaves none, HiveMQ Edge does not",
                    "reasonCode"),
            declared("subscribe-shared", "a server without Shared Subscriptions answers SUBACK 0x9E (MQTT 3.9.3); "
                    + "frontear has none (contract)", "reasonCodes"),
            declared("subscribe-subscription-identifier", "a server without Subscription Identifiers closes on one "
                    + "with DISCONNECT 0xA1 (MQTT 3.8.2.1.2, 3.2.2.3.12); frontear has none (contract)", "packet"),
            declared("subscribe-wildcard-skips-dollar", "topics starting with $ are the server's (MQTT 4.7.2), "
                    + "which puts no duty on it to refuse a client's PUBLISH to one; HiveMQ Edge closes with "
                    + "DISCONNECT 0x82, frontear accepts it and matches it to no leading wildcard, PUBACK 0x10",
                    "packet"),
            declared("unsubscribe", "0x11 is the reason code for a filter the client had no subscription to "
                    + "(MQTT 3.11.3), which frontear answers; HiveMQ Edge answers 0x00", "reasonCodes"),
            declared("publish-qos-2", "a PUBLISH above the server's Maximum QoS is closed with DISCONNECT 0x9B "
                    + "(MQTT 3.3.1.2); frontear's maximum is 1 (contract)", "packet"),
            declared("publish-topic-alias", "a Topic Alias above the server's Topic Alias Maximum is closed with "
                    + "DISCONNECT 0x94 (MQTT 3.3.2.3.4); frontear's maximum is 0 (contract)", "packet"));
    /// The release's zip, kept once per checkout beside the module's other build outputs.
    private static final Path CACHE = Path.of("build", "parity-cache");
    private static final String EDGE = "HiveMQ Edge " + EmbeddedEdge.RELEASE;
    private static final String FRONTEAR = "frontear";
    private static final System.Logger LOG = System.getLogger(FrontearParityTest.class.getName());
    /// The parity table, one row per scenario, written to the log when the class ends.
    private static final List<Row> ROWS = new CopyOnWriteArrayList<>();

    @TempDir
    static Path root;
    private static ParityBrokers brokers;

    /// Starts both brokers once for the class, when `COMANDO_PARITY` is `true`; every test is skipped otherwise.
    @BeforeAll
    static void startBrokers() throws IOException {
        assumeTrue("true".equals(System.getenv("COMANDO_PARITY")),
                "set COMANDO_PARITY=true to download HiveMQ Edge and compare it with frontear");
        brokers = ParityBrokers.start(root, CACHE);
    }

    /// Stops both brokers and writes the parity table.
    @AfterAll
    static void stopBrokers() {
        if (brokers == null) {
            return;
        }
        try {
            brokers.close();
        } finally {
            LOG.log(System.Logger.Level.INFO, table());
        }
    }

    /// frontear's six interop scenarios, written for a real broker, pass against HiveMQ Edge: they hold on the
    /// reference, so a failure of the next test is frontear's.
    @Test
    void brokerInteropPassesAgainstHiveMqEdge() throws Exception {
        interop(EDGE, brokers.edge());
    }

    /// The same six interop scenarios pass against frontear's broker.
    @Test
    void brokerInteropPassesAgainstFrontear() throws Exception {
        interop(FRONTEAR, brokers.frontear());
    }

    /// Every scenario of [ParityScenarios] gives the same packets, field by field after masking what a broker
    /// chooses, from frontear as from HiveMQ Edge; each difference left is one [#ALLOWED] names, and each allowance
    /// still matches a difference.
    @TestFactory
    Stream<DynamicTest> everyScenarioAnswersAsHiveMqEdgeDoes() {
        return ParityScenarios.ALL.stream().map(s -> DynamicTest.dynamicTest(s.name(), () -> compare(s)));
    }

    /// The differences of two transcripts, entry by entry: an entry of another connection or type, or one only one
    /// side has, is a difference of field `packet` that ends the comparison, as what follows no longer lines up;
    /// entries alike in kind differ field by field.
    static List<Diff> diff(final List<ParityRun.Event> edge, final List<ParityRun.Event> frontear) {
        final List<Diff> diffs = new ArrayList<>();
        for (int i = 0; i < Math.max(edge.size(), frontear.size()); i++) {
            final ParityRun.Event e = i < edge.size() ? edge.get(i) : null;
            final ParityRun.Event f = i < frontear.size() ? frontear.get(i) : null;
            if (e == null || f == null || !e.label().equals(f.label()) || !e.type().equals(f.type())) {
                diffs.add(new Diff(i, "packet", rest(edge, i), rest(frontear, i)));
                return diffs;
            }
            final Set<String> keys = new LinkedHashSet<>(e.fields().keySet());
            keys.addAll(f.fields().keySet());
            for (final String k : keys) {
                if (!Objects.equals(e.fields().get(k), f.fields().get(k))) {
                    diffs.add(new Diff(i, k, e.describe(), f.describe()));
                }
            }
        }
        return diffs;
    }

    private static void interop(final String broker, final InetSocketAddress at) throws Exception {
        final long startNanos = System.nanoTime();
        final Processes.Result r = ParityInterop.run(at);
        final long tookMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        LOG.log(System.Logger.Level.INFO, "BrokerInteropTest ran [broker=" + broker + ", exit=" + r.exitCode()
                + ", tookMillis=" + tookMillis + "]\n" + r.out() + r.stderr());
        assertThat(r.timedOut()).as("BrokerInteropTest against %s timed out", broker).isFalse();
        assertThat(r.exitCode()).as("BrokerInteropTest against %s:%n%s%s", broker, r.out(), r.stderr())
                .isEqualTo(ParityInterop.ALL_PASSED);
    }

    private static void compare(final ParityScenarios.ParityScenario s) throws Exception {
        final Transcript edge = run(s, EDGE, brokers.edge());
        final Transcript frontear = run(s, FRONTEAR, brokers.frontear());
        final List<Diff> diffs = diff(edge.events(), frontear.events());
        final List<String> unexplained = new ArrayList<>();
        final List<Allowance> mine = ALLOWED.stream().filter(a -> a.scenario().equals(s.name())).toList();
        final Set<Allowance> used = new LinkedHashSet<>();
        final Set<String> matched = new LinkedHashSet<>();
        for (final Diff d : diffs) {
            final Allowance a = mine.stream().filter(x -> x.fields().contains(d.field())).findFirst().orElse(null);
            if (a == null) {
                unexplained.add(d.describe());
            } else {
                used.add(a);
                matched.add(d.field());
            }
        }
        final List<String> stale = mine.stream().flatMap(a -> a.fields().stream()).filter(f -> !matched.contains(f))
                .toList();
        final Set<Verdict> verdicts = used.stream().map(Allowance::verdict).collect(Collectors.toSet());
        final String verdict = !unexplained.isEmpty() ? "UNEXPLAINED"
                : verdicts.contains(Verdict.DEFECT) ? "defect" : verdicts.isEmpty() ? "same" : "declared";
        ROWS.add(new Row(s.name(), summary(edge.events()), summary(frontear.events()), verdict,
                used.stream().map(Allowance::why).distinct().collect(Collectors.joining("; ")),
                edge.tookMillis(), frontear.tookMillis()));
        LOG.log(System.Logger.Level.INFO, "scenario compared [scenario=" + s.name() + ", verdict=" + verdict
                + ", edgeMillis=" + edge.tookMillis() + ", frontearMillis=" + frontear.tookMillis() + "]\n  "
                + EDGE + ":\n" + transcript(edge.events()) + "\n  " + FRONTEAR + ":\n" + transcript(frontear.events())
                + (diffs.isEmpty() ? "" : "\n  differences:\n" + diffs.stream().map(d -> "    " + d.describe())
                .collect(Collectors.joining("\n"))));
        assertThat(unexplained).as("differences of scenario %s (%s) that no allowance names", s.name(), s.checks())
                .isEmpty();
        assertThat(stale).as("allowed fields of scenario %s that no longer differ: remove them", s.name()).isEmpty();
    }

    private static Transcript run(final ParityScenarios.ParityScenario s, final String broker,
            final InetSocketAddress at) throws Exception {
        final String prefix = "parity/" + s.name() + '/' + UUID.randomUUID().toString().substring(0, 8);
        final long startNanos = System.nanoTime();
        try (final ParityRun r = new ParityRun(broker, at, prefix)) {
            s.body().run(r);
            return new Transcript(r.events(), Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        }
    }

    private static String rest(final List<ParityRun.Event> events, final int from) {
        return from >= events.size() ? "(nothing)" : events.subList(from, events.size()).stream()
                .map(ParityRun.Event::describe).collect(Collectors.joining(" | "));
    }

    /// A transcript as the table writes it: each entry's connection and type, with its reason codes or payload.
    private static String summary(final List<ParityRun.Event> events) {
        return events.stream().map(e -> {
            final String detail = e.fields().getOrDefault("reasonCode", e.fields().getOrDefault("reasonCodes",
                    e.fields().getOrDefault("payload", "")));
            return e.label() + ' ' + e.type() + (detail.isEmpty() ? "" : ' ' + detail);
        }).collect(Collectors.joining(", "));
    }

    private static String transcript(final List<ParityRun.Event> events) {
        return events.stream().map(e -> "    " + e.describe()).collect(Collectors.joining("\n"));
    }

    private static String table() {
        final StringBuilder b = new StringBuilder("parity table [scenarios=").append(ROWS.size()).append("]\n")
                .append("| Scenario | ").append(EDGE)
                .append(" | frontear | Verdict | Clause or defect | ms Edge/frontear |\n")
                .append("|---|---|---|---|---|---|\n");
        for (final Row r : ROWS) {
            b.append("| ").append(r.scenario()).append(" | ").append(r.edge()).append(" | ")
                    .append(r.verdict().equals("same") ? "as Edge" : r.frontear()).append(" | ").append(r.verdict())
                    .append(" | ").append(r.why()).append(" | ").append(r.edgeMillis()).append('/')
                    .append(r.frontearMillis()).append(" |\n");
        }
        return b.toString();
    }

    private static Allowance declared(final String scenario, final String clause, final String... fields) {
        return new Allowance(scenario, List.of(fields), Verdict.DECLARED, clause);
    }

    /// What a difference the comparison lets pass is.
    enum Verdict {
        /// The specification lets a broker choose, and frontear's contract says what it chose.
        DECLARED,
        /// frontear breaks the specification or its contract; reported, not fixed here.
        DEFECT
    }

    /// Differences of `fields` in `scenario` that pass, and why; each field must still differ.
    record Allowance(String scenario, List<String> fields, Verdict verdict, String why) {
    }

    /// Field `field` of transcript entry `index`, as each broker gave it.
    record Diff(int index, String field, String edge, String frontear) {

        String describe() {
            return "#" + index + ' ' + field + ": Edge " + edge + " / frontear " + frontear;
        }
    }

    private record Transcript(List<ParityRun.Event> events, long tookMillis) {
    }

    private record Row(String scenario, String edge, String frontear, String verdict, String why, long edgeMillis,
            long frontearMillis) {
    }
}
