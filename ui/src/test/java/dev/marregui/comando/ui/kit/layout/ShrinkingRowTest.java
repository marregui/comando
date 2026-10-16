package dev.marregui.comando.ui.kit.layout;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShrinkingRowTest {

    private static JComponent box(int width) {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(width, 20));
        return p;
    }

    @Test
    void chipsGiveWayInProportionAndTheRestKeepsItsWidth() {
        JComponent chipA = box(200);
        JComponent plugA = box(20);
        JComponent chipB = box(100);
        JComponent plugB = box(20);
        JPanel row = new JPanel(new ShrinkingRow(0, 50, Set.of(chipA, chipB)));
        row.add(chipA);
        row.add(plugA);
        row.add(chipB);
        row.add(plugB);

        row.setSize(340, 24);
        row.doLayout();
        assertThat(chipA.getWidth()).as("room for all").isEqualTo(200);

        row.setSize(240, 24); // 100 too few; the chips can spare 150 + 50
        row.doLayout();
        assertThat(chipA.getWidth()).isEqualTo(125);
        assertThat(chipB.getWidth()).isEqualTo(75);
        assertThat(plugA.getWidth()).isEqualTo(20);
        assertThat(plugB.getX() + plugB.getWidth()).isLessThanOrEqualTo(240);

        row.setSize(100, 24);
        row.doLayout();
        assertThat(chipA.getWidth()).as("never below the minimum").isEqualTo(50);
        assertThat(plugA.getWidth()).isEqualTo(20);
    }
}
