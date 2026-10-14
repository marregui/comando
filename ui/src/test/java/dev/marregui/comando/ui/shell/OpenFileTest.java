package dev.marregui.comando.ui.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFileTest {

    @Test
    void textOpensInAPanelWhileBinaryOrLargeFilesShowInTheSidePane(@TempDir Path dir) throws Exception {
        assertThat(OpenFiles.isText(Files.writeString(dir.resolve("a.md"), "# text ✓"))).isTrue();
        assertThat(OpenFiles.isText(Files.write(dir.resolve("a.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13}))).isFalse();
        assertThat(OpenFiles.isText(Files.write(dir.resolve("big.log"), new byte[(int) OpenFiles.MAX_PANEL_BYTES + 1]))).isFalse();
        assertThat(OpenFiles.isText(Files.write(dir.resolve("edge.txt"), "x".repeat((int) OpenFiles.MAX_PANEL_BYTES).getBytes()))).isTrue();
    }
}
