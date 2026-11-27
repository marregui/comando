package dev.marregui.comando.claude.codex;

import java.nio.file.Path;

/**
 * How comando runs a Codex session (ADR 0197).
 *
 * @param codexExecutable       the {@code codex} binary, run as {@code codex app-server}
 * @param scratchpadDir         the one folder a Codex session may write; it reads the whole disk
 * @param developerInstructions text every thread starts with, beside Codex's own; empty for none
 */
public record CodexSettings(String codexExecutable, Path scratchpadDir, String developerInstructions) {

    public CodexSettings {
        developerInstructions = developerInstructions == null ? "" : developerInstructions;
    }
}
