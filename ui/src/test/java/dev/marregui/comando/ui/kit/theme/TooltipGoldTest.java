package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TooltipGoldTest {

    @AfterEach
    void defaultGold() {
        Theme.gold(Gold.TREASURE);
    }

    @Test
    void aTooltipsEdgeIsTheReportsBarInTheGoldChosenAndItsGroundFollows() {
        GoldBarBorder border = new GoldBarBorder();
        assertThat(border.getBorderInsets(new JLabel()).left).as("clear of the bar").isGreaterThan(GoldBarBorder.BAR);
        Theme.gold(Gold.CHAMPAGNE);
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        border.paintBorder(new JLabel(), g, 0, 0, 40, 20);
        g.dispose();
        assertThat(img.getRGB(1, 0) & 0xFFFFFF).as("the bar's top").isEqualTo(Gold.CHAMPAGNE.highlight.getRGB() & 0xFFFFFF);
        assertThat(UIManager.getColor("ToolTip.background")).isEqualTo(Theme.tooltipGround());
    }

    @Test
    void choosingAGoldTellsEveryListenerOnceAndAStoppedOneNoMore() {
        List<String> heard = new ArrayList<>();
        Runnable stop = Theme.onGold(() -> heard.add(Theme.gold().name()));
        Theme.gold(Gold.YELLOW);
        Theme.gold(Gold.YELLOW);
        stop.run();
        Theme.gold(Gold.CHAMPAGNE);
        assertThat(heard).as("the same gold again is no change").containsExactly("YELLOW");
    }
}
