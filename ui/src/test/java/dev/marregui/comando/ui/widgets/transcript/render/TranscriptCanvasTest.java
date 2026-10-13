package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.testkit.SyntheticSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptCanvasTest {

    private final List<String> opened = new ArrayList<>();
    private final TranscriptCanvas canvas = new TranscriptCanvas(opened::add);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private void load(List<SyntheticSession.Step> steps) {
        int n = 0;
        for (SyntheticSession.Step s : steps) {
            if (s.index() < 0) {
                canvas.append(n++, s.block());
            } else {
                canvas.update(s.index(), s.block());
            }
        }
    }

    private void paint(int width, int height) {
        canvas.setSize(width, height);
        canvas.ensureLayout(width);
        canvas.setSize(width, canvas.contentHeight());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.translate(0, -(canvas.contentHeight() - height));
        g.setClip(0, canvas.contentHeight() - height, width, height);
        canvas.paint(g);
        g.dispose();
    }

    @Test
    void wholeSessionLaysOutAndPaints() {
        load(SyntheticSession.steps(24));
        paint(1000, 700);
        assertThat(canvas.contentHeight()).isGreaterThan(5000);
        // narrower means taller: the text wraps
        int wide = canvas.contentHeight();
        canvas.layoutFully(500);
        assertThat(canvas.contentHeight()).isGreaterThan(wide);
    }

    @Test
    void aWidthChangeLaysOutTheViewNowAndTheRestWhenIdle() {
        load(SyntheticSession.steps(10_000));
        canvas.setSize(1000, 700);
        canvas.layoutFully(1000);
        canvas.ensureLayout(600);
        assertThat(canvas.isEstimated()).isTrue();
        int guessed = canvas.contentHeight();
        canvas.refineAll();
        assertThat(canvas.isEstimated()).isFalse();
        int exact = canvas.contentHeight();
        canvas.layoutFully(600);
        assertThat(canvas.contentHeight()).isEqualTo(exact);
        // the guess is in the right range: within a factor of two of the exact height
        assertThat(guessed).isBetween(exact / 2, exact * 2);
    }

    @Test
    void subagentBlocksNestInTheirCall() {
        load(SyntheticSession.steps(10_000));
        canvas.ensureLayout(1000);
        // 8 turns of prompt, thinking, text, 5 calls, text, turn end, plus the subagent's call; its prompt and text nest
        assertThat(canvas.markdown()).contains("**Agent** · survey the module").doesNotContain("> List the classes.");
    }

    @Test
    void findOpensClosedCardsAndCountsMatches() {
        load(SyntheticSession.steps(10_000));
        paint(1000, 700);
        // tool results are closed by default; the find opens the cards that hold the text
        int matches = canvas.find("cancelSignalTimeout", true);
        assertThat(matches).isGreaterThanOrEqualTo(8);
        canvas.clearFind();
        assertThat(canvas.find("no such text anywhere", true)).isZero();
    }

    @Test
    void selectAllCopiesTextOfEveryBox() {
        load(SyntheticSession.steps(10_000));
        paint(1000, 700);
        canvas.selectAll();
        String text = canvas.selectionText();
        assertThat(text).contains("Why does the cancel path take eleven seconds?").contains("## Finding 1".substring(3));
        canvas.clearSelection();
        assertThat(canvas.selectionText()).isEmpty();
    }

    @Test
    void streamingKeepsLayoutsOfFinishedParagraphs() {
        canvas.append(0, new Block.AssistantText("m", 0, "First paragraph.\n\nSecond", true, ""));
        canvas.ensureLayout(800);
        int h1 = canvas.contentHeight();
        for (int i = 0; i < 200; i++) {
            canvas.update(0, new Block.AssistantText("m", 0, "First paragraph.\n\nSecond" + " word".repeat(i), true, ""));
            canvas.ensureLayout(800);
        }
        assertThat(canvas.contentHeight()).isGreaterThan(h1);
    }

    @Test
    void zoomRelaysOutEverything() {
        load(SyntheticSession.steps(10_000));
        canvas.ensureLayout(1000);
        int before = canvas.contentHeight();
        canvas.setZoom(4);
        canvas.layoutFully(1000);
        assertThat(canvas.contentHeight()).isGreaterThan(before);
    }

    @Test
    void tabsExpandToFourColumnStops() {
        assertThat(Rich.detab("1\tab\n\tx\tyz")).isEqualTo("1   ab\n    x   yz");
        assertThat(Rich.detab("no tabs")).isEqualTo("no tabs");
    }
}
