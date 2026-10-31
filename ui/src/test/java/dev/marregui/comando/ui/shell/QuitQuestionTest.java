package dev.marregui.comando.ui.shell;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ComandoUi#quitQuestion] words the question asked before quitting stops running work.
/// - One thing is said in a sentence; several are listed one per line.
class QuitQuestionTest {

    /// One item gives `Quitting stops ….`; two give `Quitting stops:` followed by a bullet per line.
    @Test
    void oneThingIsSaidInASentenceSeveralOnePerLine() {
        assertThat(ComandoUi.quitQuestion(List.of("man cat in notes' Terminal tab")))
                .isEqualTo("Quitting stops man cat in notes' Terminal tab.");
        assertThat(ComandoUi.quitQuestion(List.of("main's turn", "man cat in notes' Terminal tab")))
                .isEqualTo("Quitting stops:\n• main's turn\n• man cat in notes' Terminal tab");
    }
}
