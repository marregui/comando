package dev.marregui.comando.app.lab;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// The lab's window: the last 10 000 packets, numbered from 1 by the window, the oldest let go of and counted, and a
/// read after a seq that says how many after it were let go of.
class LabWindowTest {

    /// 10 050 packets in: the window keeps the last 10 000 (seq 51 to 10 050), counts 50 dropped, and a read from 0
    /// says the 50 are missed.
    @Test
    void itKeepsTheLastTenThousandAndCountsWhatItLetsGo() {
        final LabWindow window = new LabWindow();
        for (int i = 0; i < LabWindow.CAPACITY + 50; i++) {
            window.add(packet(i));
        }
        assertThat(window.dropped()).isEqualTo(50L);
        final LabWindow.Read all = window.read(0L, 20_000);
        assertThat(all.packets()).hasSize(LabWindow.CAPACITY);
        assertThat(all.packets().getFirst().seq()).isEqualTo(51L);
        assertThat(all.packets().getFirst().topic()).isEqualTo("t/50");
        assertThat(all.packets().getLast().seq()).isEqualTo(10_050L);
        assertThat(all.missed()).isEqualTo(50L);
        assertThat(all.last()).isEqualTo(10_050L);
    }

    /// The window numbers what it keeps itself, whatever seq a packet came with, and a read pages by `after` and
    /// `max`; past the end it returns nothing and its `last` is the `after` asked.
    @Test
    void itNumbersFromOneAndPagesByAfter() {
        final LabWindow window = new LabWindow(8);
        for (int i = 0; i < 5; i++) {
            assertThat(window.add(packet(i)).seq()).isEqualTo(i + 1L);
        }
        final LabWindow.Read page = window.read(1L, 2);
        assertThat(page.packets()).extracting(Packet::seq).containsExactly(2L, 3L);
        assertThat(page.missed()).isZero();
        assertThat(page.last()).isEqualTo(3L);
        final LabWindow.Read end = window.read(5L, 10);
        assertThat(end.packets()).isEmpty();
        assertThat(end.last()).isEqualTo(5L);
        assertThat(window.read(0L, 0).packets()).isEmpty();
    }

    /// A read after a seq the window let go of starts at its oldest and counts the gap.
    @Test
    void aReadBehindTheWindowCountsTheGap() {
        final LabWindow window = new LabWindow(4);
        for (int i = 0; i < 10; i++) {
            window.add(packet(i));
        }
        final LabWindow.Read read = window.read(3L, 10);
        assertThat(read.packets()).extracting(Packet::seq).containsExactly(7L, 8L, 9L, 10L);
        assertThat(read.missed()).isEqualTo(3L);
        assertThat(window.dropped()).isEqualTo(6L);
    }

    private static Packet packet(final int i) {
        return new Packet(999L, i, Kind.PUBLISHED, "pub", "", "t/" + i, 1, false, 3, 0, -1L, "", "m", "");
    }
}
