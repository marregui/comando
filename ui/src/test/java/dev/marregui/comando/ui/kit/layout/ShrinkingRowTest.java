package dev.marregui.comando.ui.kit.layout;

import java.awt.Dimension;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ShrinkingRow] lays a row out left to right and lets the named chips give way when it is too narrow.
/// - With room, every component keeps its preferred width.
/// - Short of room, chips shrink in proportion to what they can spare; the rest keeps its width.
/// - No chip shrinks below the minimum.
class ShrinkingRowTest {

    private static JComponent box(final int width) {
        final JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(width, 20));
        return p;
    }

    /// At 340 px all fit; at 240 px the 100 px shortfall is taken 3:1 from the chips' spare 150 and 50 px while the
    /// plugs keep 20 px inside the row; at 100 px the chips stop at the 50 px minimum.
    @Test
    void chipsGiveWayInProportionAndTheRestKeepsItsWidth() {
        final JComponent chipA = box(200);
        final JComponent plugA = box(20);
        final JComponent chipB = box(100);
        final JComponent plugB = box(20);
        final JPanel row = new JPanel(new ShrinkingRow(0, 50, Set.of(chipA, chipB)));
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
