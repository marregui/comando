package dev.marregui.comando.core.view;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [FileView] resolves typed paths and turns bounded UTF-8 files into Markdown or text views.
///
/// - Tilde, relative, and absolute paths resolve from the correct base.
/// - Markdown and text choose their view specification from line and extension.
/// - Binary, absent, directory, and oversized files report a path-free reason.
/// - An MP4 video becomes a video view at any size and any line, by its extension in any case.
class FileViewTest {

    /// [FileView#resolve] expands a home-relative path, resolves a relative path under `/w`, and leaves an absolute
    /// path unchanged.
    @Test
    void aPathAsTypedIsUnderHomeOrTheBaseFolder() {
        final String home = System.getProperty("user.home");
        assertThat(FileView.resolve("~/notes/a.md", "/w")).isEqualTo(Path.of(home, "notes/a.md"));
        assertThat(FileView.resolve("src/A.java", "/w")).isEqualTo(Path.of("/w/src/A.java"));
        assertThat(FileView.resolve("/abs/B.java", "/w")).isEqualTo(Path.of("/abs/B.java"));
    }

    /// [FileView#read] renders `PLAN.md` as Markdown at line zero, but returns text with its extension and requested
    /// line for Markdown at line three and Java source at line one.
    @Test
    void markdownRendersAtNoLineAndAnyTextFileIsTextAtItsLine(@TempDir final Path dir) throws IOException {
        final Path md = Files.writeString(Files.createDirectories(dir.resolve("docs")).resolve("PLAN.md"), "# plan");
        final FileView rendered = FileView.read(md, 0);
        assertThat(rendered.title()).isEqualTo("docs/PLAN.md");
        assertThat(rendered.spec()).isEqualTo(new ViewSpec.Markdown("# plan"));
        assertThat(rendered.binary()).isFalse();
        assertThat(FileView.read(md, 3).spec()).isEqualTo(new ViewSpec.Text(md.toString(), "# plan", "md", 3));
        final Path java = Files.writeString(dir.resolve("A.java"), "class A {}");
        assertThat(FileView.read(java, 1).spec()).isEqualTo(new ViewSpec.Text(java.toString(), "class A {}", "java", 1));
    }

    /// [FileView#read] identifies invalid UTF-8 bytes as a binary six-byte file and rejects missing, directory, and
    /// over-four-megabyte inputs with reasons that omit the input path.
    @Test
    void aBinaryFileIsSaidToBeOneAndEveryRefusalSaysWhyWithoutThePath(@TempDir final Path dir) throws IOException {
        final Path png = Files.write(dir.resolve("shot.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff});
        final FileView binary = FileView.read(png, 0);
        assertThat(binary.binary()).isTrue();
        assertThat(((ViewSpec.Text) binary.spec()).text()).isEqualTo("shot.png is a binary file of 6 bytes; only text files are shown.");

        assertThatThrownBy(() -> FileView.read(dir.resolve("nope.md"), 0)).hasMessage("no such file");
        assertThatThrownBy(() -> FileView.read(dir, 0)).hasMessage("not a file");
        final Path big = dir.resolve("big.log");
        try (final RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(FileView.MAX_BYTES + 1);
        }
        assertThatThrownBy(() -> FileView.read(big, 0)).hasMessage("larger than 4 MB");
    }

    /// [FileView#read] makes `clips/Demo.MP4`, over the four-megabyte text limit and asked at line seven, and
    /// `a.m4v` video views of their paths, not binary; `a.mp4.txt` and a folder named `x.mp4` are not videos.
    @Test
    void anMp4IsAVideoViewOfItsPathAtAnySizeAndLine(@TempDir final Path dir) throws IOException {
        final Path mp4 = Files.createDirectories(dir.resolve("clips")).resolve("Demo.MP4");
        try (final RandomAccessFile f = new RandomAccessFile(mp4.toFile(), "rw")) {
            f.setLength(FileView.MAX_BYTES + 1);
        }
        final FileView video = FileView.read(mp4, 7);
        assertThat(video.title()).isEqualTo("clips/Demo.MP4");
        assertThat(video.spec()).isEqualTo(new ViewSpec.Video(mp4.toString()));
        assertThat(video.spec().type()).isEqualTo("video");
        assertThat(video.binary()).isFalse();
        final Path m4v = Files.write(dir.resolve("a.m4v"), new byte[]{0, 0, 0, 0x18});
        assertThat(FileView.read(m4v, 0).spec()).isEqualTo(new ViewSpec.Video(m4v.toString()));

        assertThat(FileView.isVideo(Path.of("a.mp4.txt"))).isFalse();
        assertThatThrownBy(() -> FileView.read(Files.createDirectories(dir.resolve("x.mp4")), 0)).hasMessage("not a file");
    }
}
