package dev.marregui.comando.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuitQuestionTest {

    @Test
    void oneThingIsSaidInASentenceSeveralOnePerLine() {
        assertThat(ComandoUi.quitQuestion(List.of("man cat in notes' Terminal tab")))
                .isEqualTo("Quitting stops man cat in notes' Terminal tab.");
        assertThat(ComandoUi.quitQuestion(List.of("main's turn", "man cat in notes' Terminal tab")))
                .isEqualTo("Quitting stops:\n• main's turn\n• man cat in notes' Terminal tab");
    }
}
