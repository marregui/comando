package dev.marregui.comando.ui.kit.layout;

import java.awt.Dimension;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ShrinkingRow] lays a row out left to right and lets the named chips give way when it is too narrow.
/// - With room, every component keeps its preferred width.
/// - Short of room, the last chip gives way first, then the one before it; the rest keeps its width.
/// - No chip shrinks below the minimum.
class ShrinkingRowTest {

    private static JComponent box(final int width) {
        final JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(width, 20));
        return p;
    }

    /// At 340 px all fit; at 290 px the 50 px shortfall comes from the last chip alone, so the first chip and the plug
    /// after it stay put; at 240 px the last chip stops at the 50 px minimum and the first gives the other 50 px, while
    /// the plugs keep 20 px inside the row; at 100 px both chips stop at the minimum.
    @Test
    void theLastChipGivesWayFirstAndTheRestKeepsItsWidth() {
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

        row.setSize(290, 24); // 50 too few: the last chip can spare them
        row.doLayout();
        assertThat(chipA.getWidth()).as("the first chip keeps its width").isEqualTo(200);
        assertThat(plugA.getX()).as("so the plug after it stays put").isEqualTo(200);
        assertThat(chipB.getWidth()).isEqualTo(50);

        row.setSize(240, 24); // 100 too few; the last chip spares 50, the first the rest
        row.doLayout();
        assertThat(chipA.getWidth()).isEqualTo(150);
        assertThat(chipB.getWidth()).isEqualTo(50);
        assertThat(plugA.getWidth()).isEqualTo(20);
        assertThat(plugB.getX() + plugB.getWidth()).isLessThanOrEqualTo(240);

        row.setSize(100, 24);
        row.doLayout();
        assertThat(chipA.getWidth()).as("never below the minimum").isEqualTo(50);
        assertThat(plugA.getWidth()).isEqualTo(20);
    }
}
