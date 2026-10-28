package dev.marregui.comando.core.view;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [FileView] resolves typed paths and turns bounded UTF-8 files into Markdown or text views.
///
/// - Tilde, relative, and absolute paths resolve from the correct base.
/// - Markdown and text choose their view specification from line and extension.
/// - Binary, absent, directory, and oversized files report a path-free reason.
class FileViewTest {

    /// [FileView#resolve] expands a home-relative path, resolves a relative path under `/w`, and leaves an absolute
    /// path unchanged.
    @Test
    void aPathAsTypedIsUnderHomeOrTheBaseFolder() {
        String home = System.getProperty("user.home");
        assertThat(FileView.resolve("~/notes/a.md", "/w")).isEqualTo(Path.of(home, "notes/a.md"));
        assertThat(FileView.resolve("src/A.java", "/w")).isEqualTo(Path.of("/w/src/A.java"));
        assertThat(FileView.resolve("/abs/B.java", "/w")).isEqualTo(Path.of("/abs/B.java"));
    }

    /// [FileView#read] renders `PLAN.md` as Markdown at line zero, but returns text with its extension and requested
    /// line for Markdown at line three and Java source at line one.
    @Test
    void markdownRendersAtNoLineAndAnyTextFileIsTextAtItsLine(@TempDir Path dir) throws IOException {
        Path md = Files.writeString(Files.createDirectories(dir.resolve("docs")).resolve("PLAN.md"), "# plan");
        FileView rendered = FileView.read(md, 0);
        assertThat(rendered.title()).isEqualTo("docs/PLAN.md");
        assertThat(rendered.spec()).isEqualTo(new ViewSpec.Markdown("# plan"));
        assertThat(rendered.binary()).isFalse();
        assertThat(FileView.read(md, 3).spec()).isEqualTo(new ViewSpec.Text(md.toString(), "# plan", "md", 3));
        Path java = Files.writeString(dir.resolve("A.java"), "class A {}");
        assertThat(FileView.read(java, 1).spec()).isEqualTo(new ViewSpec.Text(java.toString(), "class A {}", "java", 1));
    }

    /// [FileView#read] identifies invalid UTF-8 bytes as a binary six-byte file and rejects missing, directory, and
    /// over-four-megabyte inputs with reasons that omit the input path.
    @Test
    void aBinaryFileIsSaidToBeOneAndEveryRefusalSaysWhyWithoutThePath(@TempDir Path dir) throws IOException {
        Path png = Files.write(dir.resolve("shot.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff});
        FileView binary = FileView.read(png, 0);
        assertThat(binary.binary()).isTrue();
        assertThat(((ViewSpec.Text) binary.spec()).text()).isEqualTo("shot.png is a binary file of 6 bytes; only text files are shown.");

        assertThatThrownBy(() -> FileView.read(dir.resolve("nope.md"), 0)).hasMessage("no such file");
        assertThatThrownBy(() -> FileView.read(dir, 0)).hasMessage("not a file");
        Path big = dir.resolve("big.log");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(FileView.MAX_BYTES + 1);
        }
        assertThatThrownBy(() -> FileView.read(big, 0)).hasMessage("larger than 4 MB");
    }
}
