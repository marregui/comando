package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.transcript.Transcript;

/** What the transcript widget renders: a session's block log. */
public record TranscriptView(String session, Transcript transcript) {
}
