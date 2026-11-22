package dev.marregui.comando.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.PagePort;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// The page tools over a fake port: the list names each panel's page, a snapshot answers the PNG's path and how to
/// see it or the port's refusal, a read answers title, file, selection and text with a closing line that says whether
/// the text was cut, `max_chars` outside its range is rejected before the port is asked, and nothing runs before the
/// application sets the port.
class PageToolsTest {

    private final RecordingSink sink = new RecordingSink();
    private final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
    private FakePages pages;
    private McpTools tools;
    @TempDir
    Path home;

    @BeforeEach
    void start() {
        tools = tools();
        pages = new FakePages();
        tools.pages(pages);
    }

    /// The list is JSON, one object per page in the port's order, with its panel's title and id, its file, and
    /// whether the panel shows it rendered.
    @Test
    void theListNamesEachPanelsPageAndWhetherItIsRendered() throws Exception {
        final McpTools.Outcome out = call("page_list", "{}");
        assertThat(out.error()).as(out.text()).isFalse();
        final JsonNode list = Json.readTree(out.text());
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("title").asString()).isEqualTo("one");
        assertThat(list.get(0).path("id").asString()).isEqualTo(States.P1.value());
        assertThat(list.get(0).path("file").asString()).isEqualTo("/tmp/report.html");
        assertThat(list.get(0).path("rendered").asBoolean()).isTrue();
        assertThat(list.get(1).path("id").asString()).isEqualTo(States.P2.value());
        assertThat(list.get(1).path("rendered").asBoolean()).isFalse();
    }

    /// A snapshot of a panel found by its title asks the port for that panel and answers the PNG's path first, then
    /// tells the agent to look at it with the Read tool.
    @Test
    void aSnapshotAnswersThePngsPathAndHowToSeeIt() throws Exception {
        final McpTools.Outcome out = call("page_snapshot", "{\"panel\":\"one\"}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(out.text().lines().findFirst()).hasValue("/tmp/page-p1.png");
        assertThat(out.text()).contains("Read tool");
        assertThat(pages.snapped).containsExactly(States.P1);
    }

    /// A snapshot the port refuses, of a panel showing its page as text, is a tool error carrying the port's words.
    @Test
    void aSnapshotThePortRefusesIsAnError() throws Exception {
        final McpTools.Outcome out = call("page_snapshot", "{\"panel\":\"two\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).isEqualTo("refused: panel p2 shows its page as text");
    }

    /// A read answers the title, the file, the user's selection and the text, then a closing line saying the text is
    /// whole; without `max_chars` the port is asked for the default.
    @Test
    void aReadAnswersTitleFileSelectionTextAndAClosingLine() throws Exception {
        pages.text = "Results\nAll green.";
        final McpTools.Outcome out = call("page_read", "{\"panel\":\"one\"}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(out.text().lines().toList()).containsExactly(
                "title: Report",
                "file: /tmp/report.html",
                "selection (10 characters):",
                "All green.",
                "text (18 characters):",
                "Results",
                "All green.",
                "[chars=18, max_chars=65536, more=false]");
        assertThat(pages.asked).containsExactly(PageTools.DEFAULT_CHARS);
    }

    /// Text the port cut at `max_chars` and marked `more` closes with `more=true`, and the port was asked for the
    /// `max_chars` the call gave.
    @Test
    void textThePortCutClosesWithMore() throws Exception {
        pages.text = "x".repeat(100);
        pages.more = true;
        final McpTools.Outcome out = call("page_read", "{\"panel\":\"one\",\"max_chars\":100}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(closing(out)).isEqualTo("[chars=100, max_chars=100, more=true]");
        assertThat(pages.asked).containsExactly(100);
    }

    /// Text longer than `max_chars`, from a port that did not cut it, is cut at `max_chars` and closes with
    /// `more=true`.
    @Test
    void textOverMaxCharsIsCutAndClosesWithMore() throws Exception {
        pages.text = "y".repeat(300);
        final McpTools.Outcome out = call("page_read", "{\"panel\":\"one\",\"max_chars\":120}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(out.text()).contains("text (120 characters):\n" + "y".repeat(120) + "\n[");
        assertThat(closing(out)).isEqualTo("[chars=120, max_chars=120, more=true]");
    }

    /// The user's empty selection reads as none, and a panel with no rendered page is a refusal naming `page_list`.
    @Test
    void noSelectionReadsAsNoneAndAPanelWithoutARenderedPageIsRefused() throws Exception {
        pages.selection = "";
        assertThat(call("page_read", "{\"panel\":\"one\"}").text()).contains("\nselection: none\n");
        final McpTools.Outcome out = call("page_read", "{\"panel\":\"two\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("refused").contains("page_list");
    }

    /// A read the page does not answer in time is a refusal carrying the port's reason, as a snapshot's is.
    @Test
    void aReadThatStallsIsARefusal() throws Exception {
        pages.stalls = true;
        final McpTools.Outcome out = call("page_read", "{\"panel\":\"one\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("refused: cannot read the page");
    }

    /// A `max_chars` below 1 or above 262144 is rejected with its value and the bounds, and the port is not asked.
    @Test
    void maxCharsOutOfRangeIsRejectedBeforeThePortIsAsked() throws Exception {
        for (final String n : List.of("0", "262145")) {
            final McpTools.Outcome out = call("page_read", "{\"panel\":\"one\",\"max_chars\":" + n + "}");
            assertThat(out.error()).as(n).isTrue();
            assertThat(out.text()).contains("max_chars is out of range").contains("max_chars=" + n)
                    .contains("max=262144");
        }
        assertThat(pages.asked).isEmpty();
        assertThat(call("page_read", "{\"panel\":\"one\",\"max_chars\":262144}").error()).isFalse();
    }

    /// A panel no open panel is called is an error that lists the panels, and the port is not asked.
    @Test
    void anUnknownPanelIsAnErrorListingThePanels() throws Exception {
        final McpTools.Outcome out = call("page_snapshot", "{\"panel\":\"nowhere\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("no open panel 'nowhere'").contains("one", "two");
        assertThat(pages.snapped).isEmpty();
    }

    /// Before the application sets the port, every page tool answers that pages are not ready, as an error.
    @Test
    void everyToolIsNotReadyBeforeThePortIsSet() throws Exception {
        tools = tools();
        for (final String tool : List.of("page_list", "page_snapshot", "page_read")) {
            final McpTools.Outcome out = call(tool, "{\"panel\":\"one\"}");
            assertThat(out.error()).as(tool).isTrue();
            assertThat(out.text()).as(tool).isEqualTo(PageTools.NOT_READY);
        }
    }

    private McpTools tools() {
        final QueryPort db = null;
        final McpTools t = new McpTools(sink, state::get, new TranscriptRegistry(), db, AuditLog.none(),
                new FakeClock(), Duration.ofMinutes(1));
        t.agentsFile(home.resolve("agents.json"));
        return t;
    }

    private McpTools.Outcome call(final String tool, final String args) throws Exception {
        return tools.call("foreman", tool, Json.readTree(args));
    }

    private static String closing(final McpTools.Outcome out) {
        return out.text().substring(out.text().lastIndexOf('\n') + 1);
    }

    /// A port with two pages: panel one's rendered, panel two's shown as text, which it refuses to draw or read. It
    /// records the panels drawn and the `maxChars` each read asks for, and answers its text whatever that asks.
    static final class FakePages implements PagePort {
        final List<PanelId> snapped = new CopyOnWriteArrayList<>();
        final List<Integer> asked = new CopyOnWriteArrayList<>();
        // set by a test, read by the port's calls on the same thread: each call runs on the calling thread
        String text = "Results";
        String selection = "All green.";
        boolean more;
        boolean stalls;

        @Override
        public List<Page> list() {
            return List.of(new Page(States.P1, "one", Path.of("/tmp/report.html"), true),
                    new Page(States.P2, "two", Path.of("/tmp/notes.html"), false));
        }

        @Override
        public String snapshot(final PanelId panel) {
            snapped.add(panel);
            return panel.equals(States.P1) ? "/tmp/page-p1.png" : "refused: panel " + panel.value()
                    + " shows its page as text";
        }

        @Override
        public Optional<Reading> read(final PanelId panel, final int maxChars) {
            asked.add(maxChars);
            if (stalls) {
                throw new IllegalStateException("cannot read the page, it did not answer in time [panel="
                        + panel.value() + ", boundMillis=5000]");
            }
            return panel.equals(States.P1)
                    ? Optional.of(new Reading("Report", Path.of("/tmp/report.html"), selection, text, more))
                    : Optional.empty();
        }
    }
}
