package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/// [MetricsLines] turns each metrics event into the QuestDB lines of its table (record 146).
///
/// - Maps a `turn` to a `comando_turn` line and a `comando_turn_model` line a model.
/// - Maps an `agent`, a `usage`, each queue of a `queues` event and a `wait` to their tables.
/// - Escapes tag and string values that hold a comma, a space, `=`, `"`, a backslash or a line break.
/// - Leaves absent, null and empty values out, and a row with no fields unsent.
/// - Gives no line for an event of an unknown type or without a timestamp.
class MetricsLinesTest {

    /** 2026-10-28T10:00:00Z in nanoseconds. */
    private static final String AT = "1793181600000000000";

    private final MetricsLines lines = new MetricsLines("/data");

    private static JsonNode event(final String text) {
        return Json.readTree(text);
    }

    /// A finished turn is one `comando_turn` line with its tags, numbers, error flag, total cost and raw usage, and
    /// one `comando_turn_model` line for its model with the model's own counts and raw object.
    @Test
    void aTurnIsATurnLineAndALinePerModel() {
        final JsonNode turn = event("""
                {"ts":"2026-10-28T10:00:00Z","event":"turn","session":"work","conversation":"conv-1",\
                "model":"claude-opus-5-5","effort":"high","subtype":"success","duration_ms":120000,"api_ms":80000,\
                "calls":4,"context":588979,"window":1000000,"queued":0,"error":false,"cost_usd":1.25,\
                "usage":{"input_tokens":10,"output_tokens":500,"service_tier":"standard"},\
                "models":{"claude-opus-5-5":{"inputTokens":10,"outputTokens":500,\
                "contextWindow":1000000,"costUSD":1.25}}}""");
        assertThat(lines.of(turn)).containsExactly(
                "comando_turn,instance=/data,session=work,conversation=conv-1,model=claude-opus-5-5,effort=high,"
                        + "subtype=success,service_tier=standard duration_ms=120000i,api_ms=80000i,calls=4i,"
                        + "context=588979i,context_window=1000000i,queued=0i,input_tokens=10i,output_tokens=500i,"
                        + "error=f,cost_usd_total=1.25,usage_raw=\"{\\\"input_tokens\\\":10,\\\"output_tokens\\\":500,"
                        + "\\\"service_tier\\\":\\\"standard\\\"}\" " + AT,
                "comando_turn_model,instance=/data,conversation=conv-1,model=claude-opus-5-5 input_tokens=10i,"
                        + "output_tokens=500i,context_window=1000000i,cost_usd=1.25,raw=\"{\\\"inputTokens\\\":10,"
                        + "\\\"outputTokens\\\":500,\\\"contextWindow\\\":1000000,\\\"costUSD\\\":1.25}\" " + AT);
    }

    /// A sub-agent's end is one `comando_agent` line: its identity as tags, its text as strings, its start as a
    /// microsecond timestamp field, and its spend's models joined by commas.
    @Test
    void anAgentIsOneAgentLine() {
        final JsonNode agent = event("""
                {"ts":"2026-10-28T10:01:30Z","event":"agent","session":"work","conversation":"conv-1",\
                "tool_use_id":"t1","type":"reviewer","description":"Review the diff","model_asked":"sonnet",\
                "parent_model":"claude-opus-5-5","status":"done","background":true,"started":"2026-10-28T10:00:00.5Z",\
                "duration_ms":90000,"tools":2,"spend":{"calls":2,"input":6,"output":50,"cache_read":300,\
                "cache_create":7,"models":["claude-sonnet-5-5","claude-haiku-4-5"]}}""");
        assertThat(lines.of(agent)).containsExactly(
                "comando_agent,instance=/data,session=work,conversation=conv-1,type=reviewer,model_asked=sonnet,"
                        + "parent_model=claude-opus-5-5,status=done tool_use_id=\"t1\",description=\"Review the diff\","
                        + "spend_models=\"claude-sonnet-5-5,claude-haiku-4-5\",background=t,started=1793181600500000t,"
                        + "duration_ms=90000i,tools=2i,spend_calls=2i,spend_input=6i,spend_output=50i,"
                        + "spend_cache_read=300i,spend_cache_create=7i 1793181690000000000");
    }

    /// A plan-usage reading is one `comando_usage` line; its `window` is the `window_name` tag.
    @Test
    void aUsageReadingIsOneUsageLine() {
        final JsonNode usage = event("""
                {"ts":"2026-10-28T10:00:00Z","event":"usage","provider":"claude","window":"five_hour","pct":26.0,\
                "resets":"2026-10-28T13:00:00Z"}""");
        assertThat(lines.of(usage)).containsExactly(
                "comando_usage,instance=/data,provider=claude,window_name=five_hour pct=26.0,"
                        + "resets=\"2026-10-28T13:00:00Z\" " + AT);
    }

    /// A `queues` event is one `comando_queue` line a queue, all at the event's time.
    @Test
    void aQueuesEventIsALinePerQueue() {
        final JsonNode queues = event("""
                {"ts":"2026-10-28T10:00:00Z","event":"queues","queues":[{"name":"intents","depth":0,"peak":12},\
                {"name":"claude-stdin-work","depth":1,"peak":3}]}""");
        assertThat(lines.of(queues)).containsExactly(
                "comando_queue,instance=/data,name=intents depth=0i,peak=12i " + AT,
                "comando_queue,instance=/data,name=claude-stdin-work depth=1i,peak=3i " + AT);
    }

    /// A closed card's `wait` is one `comando_wait` line: who asked, what and through which tool, and how it ended as
    /// tags; its id, its opening as a microsecond timestamp field and its wait in milliseconds as fields.
    @Test
    void aWaitIsOneWaitLine() {
        final JsonNode wait = event("""
                {"ts":"2026-10-28T10:00:00Z","event":"wait","session":"work","kind":"question",\
                "tool":"AskUserQuestion","outcome":"answered","card":1000001,"opened":"2026-10-28T09:58:00Z",\
                "wait_ms":120000}""");
        assertThat(lines.of(wait)).containsExactly(
                "comando_wait,instance=/data,session=work,kind=question,tool=AskUserQuestion,outcome=answered "
                        + "card=1000001i,opened=1793181480000000t,wait_ms=120000i " + AT);
    }

    /// A tag value gets a backslash before a comma, a space, `=` and a backslash, and a line break becomes an escaped
    /// space; a string value gets a backslash before `"`, a backslash and a line break. The instance's own path is a
    /// tag too. QuestDB 10.1.0 takes both lines and reads the values back as written.
    @Test
    void tagAndStringValuesAreEscaped() {
        final MetricsLines spaced = new MetricsLines("/Users/a b/.comando");
        final JsonNode agent = Json.object().put("ts", "2026-10-28T10:00:00Z").put("event", "agent")
                .put("session", "a,b c=d\"e\\f\ng").put("description", "a,b c=d\"e\\f\ng\rh");
        assertThat(spaced.of(agent)).containsExactly(
                "comando_agent,instance=/Users/a\\ b/.comando,session=a\\,b\\ c\\=d\"e\\\\f\\ g"
                        + " description=\"a,b c=d\\\"e\\\\f\\\ng\\\rh\" " + AT);
    }

    /// Absent, null and empty values are left out of a line, and an event with nothing to say but its tags, here a
    /// queue with a name only, gives no line.
    @Test
    void absentValuesAreLeftOut() {
        final JsonNode turn = event("""
                {"ts":"2026-10-28T10:00:00Z","event":"turn","conversation":"c","model":null,"effort":"",\
                "context":5,"window":"1000000","usage":{}}""");
        assertThat(lines.of(turn)).as("a quoted number is no number, an empty usage is still the raw usage")
                .containsExactly("comando_turn,instance=/data,conversation=c context=5i,usage_raw=\"{}\" " + AT);
        assertThat(lines.of(event("""
                {"ts":"2026-10-28T10:00:00Z","event":"queues","queues":[{"name":"intents"}]}"""))).isEmpty();
    }

    /// An event of a type with no table, and a known event without a readable timestamp, give no line.
    @Test
    void anUnknownEventOrOneWithoutATimeGivesNoLine() {
        assertThat(MetricsLines.known("hook")).isFalse();
        assertThat(MetricsLines.known("turn")).isTrue();
        assertThat(lines.of(event("{\"ts\":\"2026-10-28T10:00:00Z\",\"event\":\"hook\",\"name\":\"x\"}"))).isEmpty();
        assertThat(lines.of(event("{\"ts\":\"yesterday\",\"event\":\"usage\",\"pct\":1.0}"))).isEmpty();
        assertThat(lines.of(event("{\"event\":\"usage\",\"pct\":1.0}"))).isEmpty();
    }
}
