package dev.marregui.comando.ui.kit.overlay;

import dev.marregui.comando.ui.kit.async.OnEdt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JLabel;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OnEdt.class)
class OverlayTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void theLoadingBarAnimatesOnlyWhileLoading() {
        // an indeterminate bar runs its 15 ms animation timer even when hidden, so it is indeterminate only while shown
        Overlay overlay = new Overlay(new JLabel("content"));
        assertThat(overlay.animating()).as("built showing the content").isFalse();
        overlay.showLoading("Loading the schema");
        assertThat(overlay.animating()).isTrue();
        overlay.showContent();
        assertThat(overlay.animating()).isFalse();
        overlay.showLoading("Loading again");
        overlay.showEmpty("Nothing here");
        assertThat(overlay.animating()).isFalse();
        overlay.showLoading("Loading again");
        overlay.showError("boom");
        assertThat(overlay.animating()).isFalse();
        assertThat(overlay.errorText()).isEqualTo("boom");
    }
}
