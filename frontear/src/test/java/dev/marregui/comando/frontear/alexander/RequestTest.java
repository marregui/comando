package dev.marregui.comando.frontear.alexander;

import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.frontear.octopus.Direction;
import dev.marregui.comando.frontear.octopus.LinkSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// How a control request is read from its method and body:
/// - each of the four methods reads its fields
/// - an unknown method, a body that is not an object, an unknown property, a wrong type, a value out of range, a bad
///   topic filter and an unknown direction are refused with their reason, never half read
class RequestTest {

    /// `PutLink` reads the link's spec, its filter sets included.
    @Test
    void putLinkReadsTheLinkSpec() throws Exception {
        final Request request = parse("PutLink", """
                {"id":"a","host":"h","port":1883,"out":["x/#","y"],"in":[],"hopLimit":2}""");
        assertThat(request).isEqualTo(new Request.PutLink(new LinkSpec("a", "h", 1883, Set.of("x/#", "y"), Set.of(),
                2)));
    }

    /// `PutLink` reads `tls` as a flag: `true` gives a TLS link, `false` or no property a plain one, and any other
    /// value is a bad request, never a plain link.
    @Test
    void tlsIsAFlagOfPutLinkThatDefaultsToPlain() throws Exception {
        final String good = "\"id\":\"a\",\"host\":\"h\",\"port\":1883,\"out\":[],\"in\":[],\"hopLimit\":2";
        assertThat(parse("PutLink", "{" + good + ",\"tls\":true}"))
                .isEqualTo(new Request.PutLink(new LinkSpec("a", "h", 1883, Set.of(), Set.of(), 2, true)));
        assertThat(parse("PutLink", "{" + good + ",\"tls\":false}"))
                .isEqualTo(new Request.PutLink(new LinkSpec("a", "h", 1883, Set.of(), Set.of(), 2, false)));
        assertThat(parse("PutLink", "{" + good + "}"))
                .isEqualTo(new Request.PutLink(new LinkSpec("a", "h", 1883, Set.of(), Set.of(), 2, false)));
        assertRefused("PutLink", "{" + good + ",\"tls\":\"true\"}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"tls\":1}", Refusal.BAD_REQUEST);
    }

    /// `RemoveLink`, `SetFilters` and `GetState` read their fields.
    @Test
    void theOtherMethodsReadTheirFields() throws Exception {
        assertThat(parse("RemoveLink", "{\"id\":\"a\"}")).isEqualTo(new Request.RemoveLink("a"));
        assertThat(parse("SetFilters", "{\"id\":\"a\",\"direction\":\"IN\",\"filters\":[\"+/s\"]}"))
                .isEqualTo(new Request.SetFilters("a", Direction.IN, Set.of("+/s")));
        assertThat(parse("GetState", "{}")).isEqualTo(new Request.GetState());
    }

    /// A method the node does not know is refused as unknown, whatever its body.
    @Test
    void anUnknownMethodIsRefused() {
        assertRefused("Reboot", "{}", Refusal.UNKNOWN_METHOD);
        assertRefused("getState", "{}", Refusal.UNKNOWN_METHOD);
    }

    /// A body that is not an object, or that has a property the method does not define, is a bad request.
    @Test
    void aBodyThatIsNotTheMethodsObjectIsRefused() {
        assertRefused("GetState", "[]", Refusal.BAD_REQUEST);
        assertRefused("GetState", "{\"all\":true}", Refusal.BAD_REQUEST);
        assertRefused("RemoveLink", "{\"id\":\"a\",\"force\":true}", Refusal.BAD_REQUEST);
        assertRefused("RemoveLink", "\"a\"", Refusal.BAD_REQUEST);
    }

    /// A missing field, a field of the wrong type, an empty id, a port or hop limit out of range, a filter that is
    /// not a topic filter and an unknown direction are each a bad request.
    @Test
    void aWrongFieldIsRefused() {
        final String good = "\"id\":\"a\",\"host\":\"h\",\"out\":[],\"in\":[]";
        assertRefused("PutLink", "{" + good + ",\"port\":1883}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"port\":\"1883\",\"hopLimit\":1}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"port\":1883.5,\"hopLimit\":1}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"port\":0,\"hopLimit\":1}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"port\":65536,\"hopLimit\":1}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"port\":1883,\"hopLimit\":0}", Refusal.BAD_REQUEST);
        assertRefused("PutLink", "{" + good + ",\"port\":1883,\"hopLimit\":4294967296}", Refusal.BAD_REQUEST);
        assertRefused("RemoveLink", "{\"id\":\"\"}", Refusal.BAD_REQUEST);
        assertRefused("RemoveLink", "{\"id\":7}", Refusal.BAD_REQUEST);
        assertRefused("SetFilters", "{\"id\":\"a\",\"direction\":\"in\",\"filters\":[]}", Refusal.BAD_REQUEST);
        assertRefused("SetFilters", "{\"id\":\"a\",\"direction\":\"IN\",\"filters\":[\"a/#/b\"]}",
                Refusal.BAD_REQUEST);
        assertRefused("SetFilters", "{\"id\":\"a\",\"direction\":\"IN\",\"filters\":[1]}", Refusal.BAD_REQUEST);
        assertRefused("SetFilters", "{\"id\":\"a\",\"direction\":\"IN\",\"filters\":\"a\"}", Refusal.BAD_REQUEST);
    }

    private static Request parse(final String method, final String body) throws Request.Invalid {
        return Request.parse(method, Json.readTree(body));
    }

    private static void assertRefused(final String method, final String body, final Refusal refusal) {
        assertThatThrownBy(() -> parse(method, body)).as("%s %s", method, body)
                .isInstanceOfSatisfying(Request.Invalid.class, e -> assertThat(e.refusal()).isEqualTo(refusal));
    }
}
