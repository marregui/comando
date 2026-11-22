package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// A prompt another session or instance sent reads as Markdown in its bubble, under a line naming who sent it.
/// - the sender is read from the first line, `[from session …]` or `[from instance …]` with its marks, or a peer origin
/// - a first line that does not close or names no one leaves the prompt as typed
/// - the body lays out as paragraphs, parsed once and kept across widths
/// - the user's own prompts and a subagent's task stay one box of their text as typed
/// - find reads the body, and copy gives the prompt's raw text, its first line included
@ExtendWith(OnEdt.class)
class SentPromptTest {

    private static final String SESSION = "[from session foreman]\nwrite **slugify**";
    private static final String OVERSEER = "[from instance comando · overseer · session Comando UI]\n"
            + "## Plan\n\n- read the diff\n- run the tests\n\nThen report back.";

    private final TranscriptCanvas canvas = new TranscriptCanvas(_ -> { });

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /// `[from session foreman]` names a session: the label is `from foreman` and the body is what follows the line.
    @Test
    void aSessionHeaderNamesTheSendingSession() {
        final Items.PromptItem.Sender s = Items.PromptItem.sender(SESSION, "foreman", true);
        assertThat(s).isEqualTo(new Items.PromptItem.Sender("foreman", false, "", "write **slugify**"));
        assertThat(s.label()).isEqualTo("from foreman");
    }

    /// `[from instance alice · session Comando G]` names an instance and its session; without a session part, the
    /// instance alone.
    @Test
    void anInstanceHeaderNamesTheInstanceAndItsSession() {
        final String text = "[from instance alice · session Comando G]\nwhat is X?";
        assertThat(Items.PromptItem.sender(text, "peer:alice", false).label())
                .isEqualTo("from alice · Comando G");
        final Items.PromptItem.Sender bare = stored("[from instance comando]\nwhat is X?\nmore");
        assertThat(bare.label()).isEqualTo("from comando");
        assertThat(bare.body()).isEqualTo("what is X?\nmore");
    }

    /// The `overseer` mark after the instance's name reads in brackets after it, with a session or without one.
    @Test
    void anOverseerPartReadsInBracketsAfterTheInstance() {
        assertThat(stored(OVERSEER).label()).isEqualTo("from comando (overseer) · Comando UI");
        final Items.PromptItem.Sender bare = stored("[from instance comando · overseer]\nx");
        assertThat(bare).isEqualTo(new Items.PromptItem.Sender("comando", true, "", "x"));
        assertThat(bare.label()).isEqualTo("from comando (overseer)");
    }

    /// A surrogate's name holds ` · ` itself (`comando · b`): only the session and the overseer mark come off the end
    /// of the line, and the rest is the name.
    @Test
    void aSurrogatesNameKeepsItsOwnSeparator() {
        final Items.PromptItem.Sender s = stored("[from instance comando · b · session work]\nx");
        assertThat(s).isEqualTo(new Items.PromptItem.Sender("comando · b", false, "work", "x"));
        assertThat(s.label()).isEqualTo("from comando · b · work");
        assertThat(stored("[from instance comando · b · overseer · session foreman]\nx").label())
                .isEqualTo("from comando · b (overseer) · foreman");
        assertThat(stored("[from instance comando · b]\nx").label())
                .isEqualTo("from comando · b");
    }

    /// An instance named `session lab` reads as `comando · session lab`: the session is the one after the last
    /// ` · session `, and the name keeps the rest.
    @Test
    void theSessionIsTheLastSessionPart() {
        final Items.PromptItem.Sender s =
                stored("[from instance comando · session lab · session work]\nx");
        assertThat(s).isEqualTo(new Items.PromptItem.Sender("comando · session lab", false, "work", "x"));
        assertThat(s.label()).isEqualTo("from comando · session lab · work");
    }

    /// A prompt a peer instance sent without a header line is that peer's, its whole text the body.
    @Test
    void aPeersPromptWithoutAHeaderIsThePeers() {
        final Items.PromptItem.Sender s = Items.PromptItem.sender("what is X?", "peer:main", false);
        assertThat(s.label()).isEqualTo("from main");
        assertThat(s.body()).isEqualTo("what is X?");
    }

    /// A first line that does not close, names no one or is another bracket leaves the prompt as typed: no sender, and
    /// the label of the user's own prompt.
    @Test
    void aMalformedHeaderLeavesThePromptAsTyped() {
        for (final String text : List.of("[from session foreman\nwrite it", "[from session ]\nwrite it",
                "[from instance  · session work]\nwrite it", "[from someone]\nwrite it", "[from session]\nwrite it",
                "write it\n[from session foreman]")) {
            assertThat(stored(text)).as(text).isNull();
        }
        canvas.append(0, new Block.UserPrompt("[from session foreman\n# write it", "", ""));
        paint(800);
        final Items.PromptItem item = (Items.PromptItem) canvas.itemAt(0);
        assertThat(item.sent()).isFalse();
        assertThat(item.label()).isEqualTo("you");
        assertThat(boxes(item)).extracting(TextBox::text).containsExactly("[from session foreman\n# write it");
    }

    /// A transcript holding a prompt another session delivered and a prompt the user typed or pasted with the same
    /// header: the delivered one is drawn as the sender's, its body as Markdown; the user's is drawn as the user's, its
    /// text as typed, the header line included.
    @Test
    void aPastedHeaderIsTheUsersAndOnlyADeliveredPromptIsTheSenders() {
        canvas.append(0, new Block.UserPrompt(SESSION, "Main", "", Block.UNKNOWN_TIME, true));
        canvas.append(1, new Block.UserPrompt(SESSION, "Main", ""));
        paint(800);
        final Items.PromptItem delivered = (Items.PromptItem) canvas.itemAt(0);
        assertThat(delivered.sent()).isTrue();
        assertThat(delivered.label()).isEqualTo("from foreman");
        assertThat(boxes(delivered)).extracting(TextBox::text).containsExactly("write slugify");
        final Items.PromptItem pasted = (Items.PromptItem) canvas.itemAt(1);
        assertThat(pasted.sent()).isFalse();
        assertThat(pasted.label()).isEqualTo("you · Main");
        assertThat(boxes(pasted)).extracting(TextBox::text).containsExactly(SESSION);
    }

    /// The body of a sent prompt lays out as paragraphs: a heading, two list items and a paragraph make four boxes, the
    /// header line in none of them, under the label naming the sender and the time.
    @Test
    void aSentPromptsBodyLaysOutAsParagraphs() {
        final long atMillis = System.currentTimeMillis();
        canvas.append(0, new Block.UserPrompt(OVERSEER, "peer:main", "", atMillis));
        paint(800);
        final Items.PromptItem item = (Items.PromptItem) canvas.itemAt(0);
        assertThat(item.sent()).isTrue();
        assertThat(item.parsed()).isTrue();
        assertThat(item.label()).isEqualTo("from comando (overseer) · Comando UI · " + Items.clock(atMillis));
        assertThat(boxes(item)).extracting(TextBox::text)
                .containsExactly("Plan", "read the diff", "run the tests", "Then report back.");
    }

    /// A sent prompt is parsed once: a layout at another width keeps the boxes it had and wraps them to it.
    @Test
    void aSentPromptIsParsedOnceAcrossWidths() {
        final String text = OVERSEER.replace("Then report back.",
                "Then report back with what failed, what passed and what could not run, one line for each of them.");
        canvas.append(0, new Block.UserPrompt(text, "", ""));
        paint(800);
        final Items.PromptItem item = (Items.PromptItem) canvas.itemAt(0);
        final List<TextBox> before = boxes(item);
        final int wide = item.height;
        paint(200);
        final List<TextBox> after = boxes(item);
        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i)).as("box " + i).isSameAs(before.get(i));
        }
        assertThat(item.height).as("narrower, the text wraps").isGreaterThan(wide);
    }

    /// The user's own prompt keeps its Markdown characters as typed in one box, and a subagent's task too.
    @Test
    void theUsersOwnPromptAndASubagentsTaskStayAsTyped() {
        canvas.append(0, new Block.UserPrompt("# not a heading\n- nor a list", "", ""));
        paint(800);
        final Items.PromptItem own = (Items.PromptItem) canvas.itemAt(0);
        assertThat(own.sent()).isFalse();
        assertThat(own.label()).isEqualTo("you");
        assertThat(boxes(own)).extracting(TextBox::text).containsExactly("# not a heading\n- nor a list");
        final Items.PromptItem task =
                new Items.PromptItem(new Block.UserPrompt("## List the classes.", "subagent", "a1"));
        task.layout(800, Style.of(0));
        assertThat(task.sent()).isFalse();
        assertThat(task.label()).isEqualTo("task for the subagent");
        assertThat(boxes(task)).extracting(TextBox::text).containsExactly("## List the classes.");
    }

    /// Find reads a sent prompt's body box for box as it is shown, laid out or not, and finds no header text.
    @Test
    void findReadsTheBodyAsShown() {
        final Items.PromptItem unlaid = new Items.PromptItem(new Block.UserPrompt(OVERSEER, "", ""));
        final List<String> read = new ArrayList<>();
        unlaid.findTexts(read);
        assertThat(unlaid.parsed()).as("find parses without keeping the paragraphs").isFalse();
        canvas.append(0, new Block.UserPrompt(OVERSEER, "", ""));
        paint(800);
        final List<String> shown = new ArrayList<>();
        canvas.itemAt(0).findTexts(shown);
        assertThat(read).isEqualTo(shown)
                .containsExactly("Plan", "read the diff", "run the tests", "Then report back.");
        assertThat(canvas.find("run the tests", true)).isEqualTo(1);
        assertThat(canvas.find("overseer", true)).isZero();
    }

    /// Copying the transcript gives a sent prompt's raw text, its header line and Markdown included; a selection of
    /// all of it gives the body as shown.
    @Test
    void copyGivesTheRawTextWithItsHeader() {
        canvas.append(0, new Block.UserPrompt(SESSION, "foreman", "", Block.UNKNOWN_TIME, true));
        paint(800);
        final Item item = canvas.itemAt(0);
        assertThat(item.text()).isEqualTo(SESSION);
        assertThat(item.markdown()).isEqualTo("> [from session foreman]\n> write **slugify**");
        assertThat(canvas.markdown()).startsWith("> [from session foreman]\n> write **slugify**");
        canvas.selectAll();
        assertThat(canvas.selectionText()).isEqualTo("write slugify");
    }

    private void paint(final int width) {
        canvas.setSize(width, 600);
        canvas.ensureLayout(width);
        canvas.setSize(width, canvas.contentHeight());
        final BufferedImage img = new BufferedImage(width, 600, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(Theme.ground());
        g.fillRect(0, 0, width, 600);
        g.setClip(0, 0, width, 600);
        canvas.paint(g);
        g.dispose();
    }

    /// The sender of a stored prompt: no origin, no mark, its first line the only record of who sent it.
    private static Items.PromptItem.Sender stored(final String text) {
        return Items.PromptItem.sender(text, "", false);
    }

    private static List<TextBox> boxes(final Item item) {
        final List<Placed> out = new ArrayList<>();
        item.collect(0, 0, out);
        final List<TextBox> boxes = new ArrayList<>();
        for (final Placed p : out) {
            boxes.add(p.box());
        }
        return boxes;
    }
}
