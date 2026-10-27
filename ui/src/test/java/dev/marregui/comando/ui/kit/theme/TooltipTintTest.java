package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TooltipTintTest {

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
    }

    @Test
    void aTooltipsEdgeIsTheReportsBarInTheTintChosenOnTheTranscriptsGround() {
        TintBarBorder border = new TintBarBorder();
        assertThat(border.getBorderInsets(new JLabel()).left).as("clear of the bar").isGreaterThan(TintBarBorder.BAR);
        Theme.tint(Tint.CHAMPAGNE);
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        border.paintBorder(new JLabel(), g, 0, 0, 40, 20);
        g.dispose();
        assertThat(img.getRGB(1, 0) & 0xFFFFFF).as("the bar's top").isEqualTo(Tint.CHAMPAGNE.highlight.getRGB() & 0xFFFFFF);
        Color ground = UIManager.getColor("ToolTip.background");
        Theme.tint(Tint.ELECTRIC_BLUE);
        assertThat(UIManager.getColor("ToolTip.background")).as("the ground is no tint's: a choice leaves it").isEqualTo(ground);
        BufferedImage blue = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D b = blue.createGraphics();
        border.paintBorder(new JLabel(), b, 0, 0, 40, 20);
        b.dispose();
        assertThat(blue.getRGB(1, 0) & 0xFFFFFF).as("a tint that is no gold").isEqualTo(Tint.ELECTRIC_BLUE.highlight.getRGB() & 0xFFFFFF);
    }

    @Test
    void choosingATintTellsEveryListenerOnceAndAStoppedOneNoMore() {
        List<String> heard = new ArrayList<>();
        Runnable stop = Theme.onTint(() -> heard.add(Theme.tint().name()));
        Theme.tint(Tint.YELLOW);
        Theme.tint(Tint.YELLOW);
        stop.run();
        Theme.tint(Tint.CHAMPAGNE);
        assertThat(heard).as("the same tint again is no change").containsExactly("YELLOW");
    }

    @Test
    void everyTintReadsOnTheTranscriptsGroundAndAttentionStaysTreasureGold() {
        for (Tint t : Tint.values()) {
            assertThat(contrast(t.body, Theme.ground())).as(t + "'s body").isGreaterThan(8);
            assertThat(contrast(t.shade, Theme.ground())).as(t + "'s shade").isGreaterThan(4.5);
            assertThat(contrast(t.highlight, t.tableHeadGround())).as(t + "'s table head").isGreaterThan(7);
            Theme.tint(t);
            assertThat(Theme.ATTENTION).as("attention in " + t).isEqualTo(Tint.TREASURE.body);
        }
        assertThat(Tint.values()).extracting(t -> t.label).containsExactly(
                "Treasure gold", "18k yellow gold", "Champagne gold", "Silver", "Tropical forest", "Electric blue");
    }

    @Test
    void everyTintReadsOnEveryGroundAndAGroundChosenWithoutTheLookIsOnlyKept() {
        for (Ground g : Ground.values()) {
            assertThat(contrast(Theme.TRANSCRIPT_TEXT, g.color)).as("ivory on " + g).isGreaterThan(12);
            assertThat(contrast(Theme.CARD, g.color)).as("a card stands off " + g).isGreaterThan(1.2);
            for (Tint t : Tint.values()) {
                assertThat(contrast(t.body, g.color)).as(t + "'s body on " + g).isGreaterThan(7.5);
                assertThat(contrast(t.shade, g.color)).as(t + "'s shade on " + g).isGreaterThan(4);
            }
        }
        assertThat(Ground.named("BEIGE")).isEqualTo(Ground.GRAPHITE);
        List<String> heard = new ArrayList<>();
        Runnable stop = Theme.onTint(() -> heard.add(Theme.groundChosen().name()));
        Theme.ground(Ground.MIDNIGHT);
        assertThat(Theme.ground()).isEqualTo(Ground.MIDNIGHT.color);
        assertThat(Tint.TREASURE.tableHeadGround()).as("a band mixed over the ground chosen")
                .isEqualTo(Tint.over(Ground.MIDNIGHT.color, Tint.TREASURE.shade, 0.22f));
        Theme.ground(Ground.GRAPHITE);
        stop.run();
        assertThat(heard).as("what listens to the tint hears a ground chosen").containsExactly("MIDNIGHT", "GRAPHITE");
    }

    /** WCAG's contrast ratio of {@code a} over {@code b}. */
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
