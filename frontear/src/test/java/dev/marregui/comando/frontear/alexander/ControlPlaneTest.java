package dev.marregui.comando.frontear.alexander;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.frontear.gateway.Broker;
import dev.marregui.comando.frontear.gateway.BrokerOptions;
import dev.marregui.comando.frontear.gateway.InternalSubscriber;
import dev.marregui.comando.frontear.octopus.Direction;
import dev.marregui.comando.frontear.octopus.LinkSpec;
import dev.marregui.comando.frontear.octopus.LinkState;
import dev.marregui.comando.frontear.protocol.Codec;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// A node's control plane on a real broker, its mesh a fake and its clock the test's:
/// - a signed `PutLink` is applied and answered on its response topic with its correlation data
/// - unsigned, an unknown key, a tampered payload, a tampered topic, a ts outside 30 s and a replayed nonce are each
///   refused with their reason in the PUBACK and the reply, counted, and never reach the mesh
/// - `GetState` answers with the mesh's state; `RemoveLink` and `SetFilters` reach the mesh; an unknown method, a bad
///   body and a failing mesh are refused with their reason
/// - the state topic is republished on change, at most once a second by the injected clock
/// - a request from before a restart is refused as stale
/// - no reply goes to a reserved topic, and close is idempotent
/// - requests waiting for their check are bounded in count and bytes, in all and per origin: past a bound a request is
///   refused as busy, and one origin's flood leaves room for another's genuine request
/// - a mesh that throws an `Error` still lets the PUBACK go
class ControlPlaneTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int MIB = 1024 * 1024;
    private static final String NODE = "n1";
    private static final String CONTROL = "$frontear/control/" + NODE + "/";
    private static final String STATE = "$frontear/state/" + NODE + "/links";
    private static final long START_MILLIS = 1_800_000_000_000L;
    private static final List<Packet.Publish> REPLIES = new CopyOnWriteArrayList<>();
    private static final List<Packet.Publish> STATES = new CopyOnWriteArrayList<>();

    private static KeyPair trustedKeys;
    private static KeyPair otherKeys;
    private static String trustedId;
    private static Broker broker;

    private TestClock clock;
    private FakeMesh mesh;
    private int nonces;

    @BeforeAll
    static void start() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        trustedKeys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
        trustedId = Signer.keyId(trustedKeys.getPublic());
        broker = Broker.start(new BrokerOptions(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 65_535,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 100, InstantSource.system()));
        broker.subscribe("test-replies", "replies/#", 1, false, collect(REPLIES));
        broker.subscribe("test-states", "$frontear/state/#", 1, false, collect(STATES));
    }

    @AfterAll
    static void stop() {
        broker.close();
    }

    @BeforeEach
    void reset() {
        REPLIES.clear();
        STATES.clear();
        clock = new TestClock(START_MILLIS);
        mesh = new FakeMesh();
    }

    /// A signed `PutLink` reaches the mesh with its spec, its PUBACK is success, and its reply `{"ok":true}` comes on
    /// its response topic, JSON, with its correlation data unchanged.
    @Test
    void aSignedPutLinkIsAppliedAndAnsweredWithItsCorrelationData() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish request = request("PutLink", putLink("a"), clock.millis());
            assertThat(send(request)).isEqualTo(Packet.SUCCESS);
            assertThat(mesh.puts).containsExactly(new LinkSpec("a", "peer", 1883, Set.of("x/#"), Set.of(), 2));
            final Packet.Publish reply = reply(request);
            assertThat(reply.topic()).isEqualTo("replies/PutLink");
            assertThat(reply.retain()).isFalse();
            assertThat(reply.meta().contentType()).isEqualTo(Names.JSON);
            assertThat(Json.readTree(reply.payload()).path("ok").booleanValue()).isTrue();
            assertThat(plane.accepted()).isEqualTo(1);
        }
    }

    /// A message without the signature properties is refused as unsigned.
    @Test
    void anUnsignedRequestIsRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish signed = request("PutLink", putLink("a"), clock.millis());
            assertRefused(plane, SealTest.withProperties(signed, List.of()), Refusal.UNSIGNED);
        }
    }

    /// A message signed by a key the node does not trust is refused as an unknown key.
    @Test
    void aRequestSignedByAnUnknownKeyIsRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish request = Seal.seal(otherKeys.getPrivate(), Signer.keyId(otherKeys.getPublic()),
                    CONTROL + "PutLink", clock.millis(), nonce(), putLink("a"), "replies/PutLink", correlation());
            assertRefused(plane, request, Refusal.UNKNOWN_KEY);
        }
    }

    /// A payload changed after signing does not verify.
    @Test
    void aTamperedPayloadIsRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish signed = request("PutLink", putLink("a"), clock.millis());
            assertRefused(plane, SealTest.with(signed, signed.topic(), putLink("b"), signed.meta()),
                    Refusal.BAD_SIGNATURE);
        }
    }

    /// A request signed for another node, delivered on this node's topic, does not verify.
    @Test
    void aTamperedTopicIsRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish signed = Seal.seal(trustedKeys.getPrivate(), trustedId,
                    "$frontear/control/n2/PutLink", clock.millis(), nonce(), putLink("a"), "replies/PutLink",
                    correlation());
            assertRefused(plane, SealTest.with(signed, CONTROL + "PutLink", signed.payload(), signed.meta()),
                    Refusal.BAD_SIGNATURE);
        }
    }

    /// A ts 30,001 ms behind or ahead of the node's clock is refused as stale, each one counted.
    @Test
    void aTsOutsideThirtySecondsIsRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final long window = Seal.WINDOW.toMillis();
            assertRefused(plane, request("PutLink", putLink("a"), clock.millis() - window - 1), Refusal.STALE);
            REPLIES.clear();
            final Packet.Publish ahead = request("PutLink", putLink("a"), clock.millis() + window + 1);
            assertThat(send(ahead)).isEqualTo(Packet.NOT_AUTHORIZED);
            assertThat(plane.refused(Refusal.STALE)).isEqualTo(2);
            assertThat(mesh.puts).isEmpty();
        }
    }

    /// The same signed request delivered twice is applied once; the second is refused as replayed.
    @Test
    void aReplayedNonceIsRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish request = request("PutLink", putLink("a"), clock.millis());
            assertThat(send(request)).isEqualTo(Packet.SUCCESS);
            assertThat(reply(request).payload()).isNotNull();
            REPLIES.clear();
            assertThat(send(request)).isEqualTo(Packet.NOT_AUTHORIZED);
            assertThat(reason(reply(request))).isEqualTo(Refusal.REPLAYED.code());
            assertThat(plane.refused(Refusal.REPLAYED)).isEqualTo(1);
            assertThat(mesh.puts).hasSize(1);
        }
    }

    /// `GetState` answers with every link of the mesh and its fields.
    @Test
    void getStateAnswersWithTheMeshState() throws Exception {
        mesh.state = List.of(new LinkState("a", true, 1, 2, 3, 4, 5, 6));
        try (ControlPlane plane = plane()) {
            final Packet.Publish request = request("GetState", "{}".getBytes(StandardCharsets.UTF_8),
                    clock.millis());
            assertThat(send(request)).isEqualTo(Packet.SUCCESS);
            final JsonNode body = Json.readTree(reply(request).payload());
            assertThat(body.path("ok").booleanValue()).isTrue();
            final JsonNode links = body.path("links");
            assertThat(links.size()).isEqualTo(1);
            final JsonNode a = links.path(0);
            assertThat(a.path("id").stringValue()).isEqualTo("a");
            assertThat(a.path("connected").booleanValue()).isTrue();
            assertThat(List.of(a.path("queued").asLong(), a.path("cursor").asLong(), a.path("tail").asLong(),
                    a.path("sent").asLong(), a.path("dropped").asLong(), a.path("reordered").asLong()))
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            assertThat(plane.accepted()).isEqualTo(1);
        }
    }

    /// `RemoveLink` and `SetFilters` reach the mesh with their fields.
    @Test
    void removeLinkAndSetFiltersReachTheMesh() throws Exception {
        try (ControlPlane plane = plane()) {
            assertThat(send(request("SetFilters", bytes("{\"id\":\"a\",\"direction\":\"OUT\",\"filters\":[\"s/+\"]}"),
                    clock.millis()))).isEqualTo(Packet.SUCCESS);
            assertThat(send(request("RemoveLink", bytes("{\"id\":\"a\"}"), clock.millis())))
                    .isEqualTo(Packet.SUCCESS);
            assertThat(mesh.filters).containsExactly(new FakeMesh.Filters("a", Direction.OUT, Set.of("s/+")));
            assertThat(mesh.removes).containsExactly("a");
            assertThat(plane.accepted()).isEqualTo(2);
        }
    }

    /// A signed request for a method the node does not know, with a body that is not JSON, or that the mesh throws
    /// on, is refused with its own reason and code, and answered.
    @Test
    void anUnknownMethodABadBodyAndAMeshFailureAreRefused() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish unknown = request("Reboot", bytes("{}"), clock.millis());
            assertThat(send(unknown)).isEqualTo(Refusal.UNKNOWN_METHOD.reasonCode());
            assertThat(reason(reply(unknown))).isEqualTo(Refusal.UNKNOWN_METHOD.code());
            final Packet.Publish bad = request("PutLink", bytes("{\"id\":"), clock.millis());
            assertThat(send(bad)).isEqualTo(Refusal.BAD_REQUEST.reasonCode());
            assertThat(reason(reply(bad))).isEqualTo(Refusal.BAD_REQUEST.code());
            mesh.failure = new IllegalArgumentException("no such link [id=a]");
            final Packet.Publish failing = request("RemoveLink", bytes("{\"id\":\"a\"}"), clock.millis());
            assertThat(send(failing)).isEqualTo(Refusal.FAILED.reasonCode());
            assertThat(reason(reply(failing))).isEqualTo(Refusal.FAILED.code());
            assertThat(plane.refused(Refusal.UNKNOWN_METHOD)).isEqualTo(1);
            assertThat(plane.refused(Refusal.BAD_REQUEST)).isEqualTo(1);
            assertThat(plane.refused(Refusal.FAILED)).isEqualTo(1);
            assertThat(plane.accepted()).isZero();
        }
    }

    /// A request whose response topic is the node's own state topic is applied but not answered there, so a signed
    /// request cannot make the plane overwrite its state.
    @Test
    void aResponseTopicUnderFrontearGetsNoReply() throws Exception {
        try (ControlPlane plane = plane()) {
            final Packet.Publish request = Seal.seal(trustedKeys.getPrivate(), trustedId, CONTROL + "GetState",
                    clock.millis(), nonce(), bytes("{}"), STATE, correlation());
            assertThat(send(request)).isEqualTo(Packet.SUCCESS);
            final Packet.Publish barrier = request("GetState", bytes("{}"), clock.millis());
            assertThat(send(barrier)).isEqualTo(Packet.SUCCESS);
            reply(barrier);
            for (final Packet.Publish state : STATES) {
                assertThat(Json.readTree(state.payload()).has("ok")).as("a reply on the state topic").isFalse();
            }
            assertThat(plane.accepted()).isEqualTo(2);
        }
    }

    /// The state topic is published at start; a change one test-clock millisecond later is held, and published by
    /// the plane's own timer once the injected clock reaches one second after the last publish.
    @Test
    void theStateTopicIsRepublishedOnChangeAtMostOnceASecond() throws Exception {
        try (ControlPlane plane = plane()) {
            await().atMost(WAIT).until(() -> STATES.size() == 1);
            final Packet.Publish first = STATES.getFirst();
            assertThat(first.topic()).isEqualTo(STATE);
            assertThat(first.retain()).isTrue();
            assertThat(Json.readTree(first.payload()).path("links").size()).isZero();
            clock.advance(Duration.ofMillis(1));
            assertThat(send(request("PutLink", putLink("a"), clock.millis()))).isEqualTo(Packet.SUCCESS);
            assertThat(STATES).as("held within the second").hasSize(1);
            clock.set(START_MILLIS + StateTopic.EVERY.toMillis());
            await().atMost(WAIT).until(() -> STATES.size() == 2);
            final JsonNode links = Json.readTree(STATES.get(1).payload()).path("links");
            assertThat(links.size()).isEqualTo(1);
            assertThat(links.path(0).path("id").stringValue()).isEqualTo("a");
            assertThat(plane.accepted()).isEqualTo(1);
        }
    }

    /// A request the plane applied, replayed to the node's next plane started 5 s later as after a restart, is refused
    /// as stale though its ts is within 30 s, since the new plane never saw its nonce; a request signed after the
    /// restart is applied.
    @Test
    void aRequestFromBeforeARestartIsRefusedAsStale() throws Exception {
        final Packet.Publish request = request("PutLink", putLink("a"), clock.millis());
        try (ControlPlane first = plane()) {
            assertThat(send(request)).isEqualTo(Packet.SUCCESS);
            assertThat(first.accepted()).isEqualTo(1);
        }
        clock.advance(Duration.ofSeconds(5));
        REPLIES.clear();
        try (ControlPlane plane = plane()) {
            assertThat(send(request)).isEqualTo(Packet.NOT_AUTHORIZED);
            assertThat(reason(reply(request))).isEqualTo(Refusal.STALE.code());
            assertThat(plane.refused(Refusal.STALE)).isEqualTo(1);
            assertThat(send(request("PutLink", putLink("b"), clock.millis()))).isEqualTo(Packet.SUCCESS);
        }
        assertThat(mesh.puts).extracting(LinkSpec::id).containsExactly("a", "b");
    }

    /// With the mesh holding the first request, one origin has ORIGIN_PENDING_MAX forged requests waiting and its
    /// next is refused at once as busy with 0x97; a genuine request from another origin is still admitted, and once
    /// the mesh lets go every waiting request is handled and nothing waits.
    @Test
    void pastItsShareOneOriginIsRefusedAsBusyAndAnotherIsStillAdmitted() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        mesh.hold = release;
        try (ControlPlane plane = plane()) {
            final CompletableFuture<Integer> held = broker.publish(request("PutLink", putLink("a"), clock.millis()),
                    "pod");
            final Packet.Publish forged = forged(0);
            final List<CompletableFuture<Integer>> flood = new ArrayList<>();
            for (int i = 0; i < ControlPlane.ORIGIN_PENDING_MAX; i++) {
                flood.add(broker.publish(forged, "flood"));
            }
            assertThat(get(broker.publish(forged, "flood"))).isEqualTo(Refusal.BUSY.reasonCode());
            assertThat(plane.refused(Refusal.BUSY)).isEqualTo(1);
            final CompletableFuture<Integer> genuine = broker.publish(request("PutLink", putLink("b"),
                    clock.millis()), "pod");
            assertThat(plane.stats().pending()).isEqualTo(ControlPlane.ORIGIN_PENDING_MAX + 2);
            assertThat(plane.stats().origins()).isEqualTo(2);
            release.countDown();
            for (final CompletableFuture<Integer> future : flood) {
                assertThat(get(future)).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(get(held)).isEqualTo(Packet.SUCCESS);
            assertThat(get(genuine)).isEqualTo(Packet.SUCCESS);
            assertThat(plane.refused(Refusal.BAD_SIGNATURE)).isEqualTo(ControlPlane.ORIGIN_PENDING_MAX);
            assertThat(mesh.puts).extracting(LinkSpec::id).containsExactly("a", "b");
            assertNothingWaits(plane);
        }
    }

    /// With the mesh holding the first request, PENDING_MAX requests from several origins, none past its share, wait
    /// and the next, from an origin with nothing waiting, is refused as busy.
    @Test
    void pastItsBoundARequestIsRefusedAsBusy() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        mesh.hold = release;
        try (ControlPlane plane = plane()) {
            final List<CompletableFuture<Integer>> waiting = new ArrayList<>();
            waiting.add(broker.publish(request("PutLink", putLink("a"), clock.millis()), "pod"));
            final Packet.Publish forged = forged(0);
            for (int origin = 0; waiting.size() < ControlPlane.PENDING_MAX; origin++) {
                for (int i = 0; i < ControlPlane.ORIGIN_PENDING_MAX && waiting.size() < ControlPlane.PENDING_MAX;
                        i++) {
                    waiting.add(broker.publish(forged, "o" + origin));
                }
            }
            assertThat(plane.stats().pending()).isEqualTo(ControlPlane.PENDING_MAX);
            assertThat(get(broker.publish(forged, "fresh"))).isEqualTo(Refusal.BUSY.reasonCode());
            assertThat(plane.refused(Refusal.BUSY)).isEqualTo(1);
            release.countDown();
            assertThat(get(waiting.getFirst())).isEqualTo(Packet.SUCCESS);
            for (final CompletableFuture<Integer> future : waiting.subList(1, waiting.size())) {
                assertThat(get(future)).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(mesh.puts).hasSize(1);
            assertNothingWaits(plane);
        }
    }

    /// With the mesh holding the first request, forged requests of 1 MiB from one origin wait only up to its byte
    /// share and the next is refused as busy, while a genuine request from another origin is still admitted.
    @Test
    void pastItsByteShareOneOriginIsRefusedAsBusyAndAnotherIsStillAdmitted() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        mesh.hold = release;
        try (ControlPlane plane = plane()) {
            final CompletableFuture<Integer> held = broker.publish(request("PutLink", putLink("a"), clock.millis()),
                    "pod");
            final Packet.Publish forged = forged(MIB);
            final long fit = ControlPlane.ORIGIN_PENDING_BYTES_MAX / forged.weight();
            final List<CompletableFuture<Integer>> flood = new ArrayList<>();
            for (int i = 0; i < fit; i++) {
                flood.add(broker.publish(forged, "flood"));
            }
            assertThat(get(broker.publish(forged, "flood"))).isEqualTo(Refusal.BUSY.reasonCode());
            assertThat(plane.stats().pendingBytes()).isLessThanOrEqualTo(ControlPlane.ORIGIN_PENDING_BYTES_MAX
                    + request("PutLink", putLink("a"), clock.millis()).weight());
            final CompletableFuture<Integer> genuine = broker.publish(request("PutLink", putLink("b"),
                    clock.millis()), "pod");
            release.countDown();
            for (final CompletableFuture<Integer> future : flood) {
                assertThat(get(future)).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(get(held)).isEqualTo(Packet.SUCCESS);
            assertThat(get(genuine)).isEqualTo(Packet.SUCCESS);
            assertThat(plane.refused(Refusal.BUSY)).isEqualTo(1);
            assertNothingWaits(plane);
        }
    }

    /// With the mesh holding the first request, origins each within their byte share fill the plane's byte bound:
    /// a request of half a share from an origin with nothing waiting is refused as busy, a small one still fits.
    @Test
    void pastItsByteBoundARequestIsRefusedAsBusy() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        mesh.hold = release;
        try (ControlPlane plane = plane()) {
            final List<CompletableFuture<Integer>> waiting = new ArrayList<>();
            waiting.add(broker.publish(request("PutLink", putLink("a"), clock.millis()), "pod"));
            final Packet.Publish small = forged(0);
            final Packet.Publish half = forged(Math.toIntExact(ControlPlane.ORIGIN_PENDING_BYTES_MAX / 2
                    - small.weight()));
            assertThat(half.weight()).isEqualTo(ControlPlane.ORIGIN_PENDING_BYTES_MAX / 2);
            // 7 halves: 3.5 shares, so with the first request less than half a share is left
            for (int i = 0; i < 7; i++) {
                waiting.add(broker.publish(half, "o" + i / 2));
            }
            assertThat(get(broker.publish(half, "o4"))).isEqualTo(Refusal.BUSY.reasonCode());
            waiting.add(broker.publish(small, "o5"));
            assertThat(plane.stats().pendingBytes()).isLessThanOrEqualTo(ControlPlane.PENDING_BYTES_MAX);
            release.countDown();
            assertThat(get(waiting.getFirst())).isEqualTo(Packet.SUCCESS);
            for (final CompletableFuture<Integer> future : waiting.subList(1, waiting.size())) {
                assertThat(get(future)).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(plane.refused(Refusal.BUSY)).isEqualTo(1);
            assertNothingWaits(plane);
        }
    }

    /// With the mesh holding the first request, forged requests of 20,000 empty user properties each, 5 bytes apiece
    /// on the wire and a list entry, two strings and a record apiece in the heap, are weighed by what they hold: one
    /// origin's waiting requests stay within its byte share on the wire, where counting only their characters
    /// admitted them up to the count bound, a hundred times past the share.
    @Test
    void requestsOfManyEmptyUserPropertiesWaitWithinTheOriginsByteShare() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        mesh.hold = release;
        try (ControlPlane plane = plane()) {
            final CompletableFuture<Integer> held = broker.publish(request("PutLink", putLink("a"), clock.millis()),
                    "pod");
            final Packet.Publish signed = forged(0);
            final List<Packet.UserProperty> properties = new ArrayList<>(signed.meta().userProperties());
            for (int i = 0; i < 20_000; i++) {
                properties.add(new Packet.UserProperty("", ""));
            }
            final Packet.Publish forged = SealTest.withProperties(signed, properties);
            // as a client sends it, at QoS 1
            final long wire = Codec.encode(new Packet.Publish(forged.topic(), forged.payload(), 1, false, false, 1,
                    forged.responseTopic(), forged.correlation(), forged.meta())).length;
            final List<CompletableFuture<Integer>> flood = new ArrayList<>();
            CompletableFuture<Integer> last = broker.publish(forged, "flood");
            while (!last.isDone() && flood.size() < ControlPlane.ORIGIN_PENDING_MAX) {
                flood.add(last);
                last = broker.publish(forged, "flood");
            }
            assertThat(get(last)).isEqualTo(Refusal.BUSY.reasonCode());
            assertThat(flood).hasSize(Math.toIntExact(ControlPlane.ORIGIN_PENDING_BYTES_MAX / forged.weight()));
            assertThat(flood.size() * wire).isLessThanOrEqualTo(ControlPlane.ORIGIN_PENDING_BYTES_MAX);
            release.countDown();
            for (final CompletableFuture<Integer> future : flood) {
                assertThat(get(future)).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(get(held)).isEqualTo(Packet.SUCCESS);
            assertNothingWaits(plane);
        }
    }

    /// A mesh that throws an `Error` still lets the request's PUBACK go, with 0x80, so the client's later PUBACKs do
    /// not wait behind it.
    @Test
    void aRequestTheMeshFailsWithAnErrorIsStillAcknowledged() throws Exception {
        mesh.putError = new AssertionError("mesh broke");
        try (ControlPlane plane = plane()) {
            assertThat(send(request("PutLink", putLink("a"), clock.millis()))).isEqualTo(Packet.UNSPECIFIED);
            assertNothingWaits(plane);
        }
    }

    /// A second close does nothing, and after close no request reaches the plane.
    @Test
    void closeIsIdempotentAndEndsTheSubscription() throws Exception {
        final ControlPlane plane = plane();
        plane.close();
        plane.close();
        assertThat(send(request("PutLink", putLink("a"), clock.millis()))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        assertThat(mesh.puts).isEmpty();
    }

    /// A node name that is not one topic level is refused before anything starts.
    @Test
    void aNodeThatIsNotOneTopicLevelIsRefused() {
        for (final String node : List.of("", "a/b", "+", "#")) {
            assertThatThrownBy(() -> new ControlPlane(broker, mesh, node, Set.of(trustedKeys.getPublic()), clock))
                    .as(node).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static int send(final Packet.Publish request) throws Exception {
        return get(broker.publish(request, "pod"));
    }

    private static int get(final CompletableFuture<Integer> code) throws Exception {
        return code.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /// Once every handled request has been let go, nothing waits for the strand.
    private static void assertNothingWaits(final ControlPlane plane) {
        await().atMost(WAIT).until(() -> plane.stats().pending() == 0);
        assertThat(plane.stats()).isEqualTo(new ControlPlane.Stats(0, ControlPlane.PENDING_MAX, 0,
                ControlPlane.PENDING_BYTES_MAX, 0));
    }

    /// The reply carrying the correlation data of `request`, waited for.
    private static Packet.Publish reply(final Packet.Publish request) {
        return await().atMost(WAIT).until(() -> {
            for (final Packet.Publish reply : REPLIES) {
                if (Arrays.equals(reply.correlation(), request.correlation())) {
                    return reply;
                }
            }
            return null;
        }, reply -> reply != null);
    }

    private static String reason(final Packet.Publish reply) {
        final JsonNode body = Json.readTree(reply.payload());
        assertThat(body.path("ok").booleanValue()).isFalse();
        return body.path("reason").stringValue();
    }

    private static byte[] putLink(final String id) {
        return bytes("{\"id\":\"" + id + "\",\"host\":\"peer\",\"port\":1883,\"out\":[\"x/#\"],\"in\":[],"
                + "\"hopLimit\":2}");
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static InternalSubscriber collect(final List<Packet.Publish> into) {
        return (message, _) -> {
            into.add(message);
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        };
    }

    private ControlPlane plane() {
        return new ControlPlane(broker, mesh, NODE, Set.of(trustedKeys.getPublic()), clock);
    }

    private void assertRefused(final ControlPlane plane, final Packet.Publish request, final Refusal refusal)
            throws Exception {
        assertThat(send(request)).isEqualTo(refusal.reasonCode());
        assertThat(reason(reply(request))).isEqualTo(refusal.code());
        assertThat(plane.refused(refusal)).isEqualTo(1);
        assertThat(plane.accepted()).isZero();
        assertThat(mesh.puts).isEmpty();
    }

    private Packet.Publish request(final String method, final byte[] body, final long tsMillis) {
        return Seal.seal(trustedKeys.getPrivate(), trustedId, CONTROL + method, tsMillis, nonce(), body,
                "replies/" + method, correlation());
    }

    /// A GetState request whose payload, `payloadBytes` zeros, was swapped after signing, with no reply route: its
    /// signature is checked and fails.
    private Packet.Publish forged(final int payloadBytes) {
        final Packet.Publish signed = request("GetState", bytes("{}"), clock.millis());
        return SealTest.route(SealTest.with(signed, signed.topic(), new byte[payloadBytes], signed.meta()), null,
                null);
    }

    private String nonce() {
        return "%032x".formatted(++nonces);
    }

    private byte[] correlation() {
        return ("c" + nonces).getBytes(StandardCharsets.UTF_8);
    }
}
