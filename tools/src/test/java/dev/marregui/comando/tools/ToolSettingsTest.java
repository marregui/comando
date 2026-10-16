package dev.marregui.comando.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSettingsTest {

    private static Path script(Path dir, String name) throws Exception {
        Path p = Files.writeString(Files.createDirectories(dir).resolve(name), "#!/bin/sh\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    @Test
    void gradleRunsThroughTheKitsWrapperUnlessTheUserSetsAnotherOrNone(@TempDir Path dir) throws Exception {
        Path kit = script(dir.resolve("kit/bin"), "gradle-serial");
        Path own = script(dir.resolve("mine"), "wrapper");

        assertThat(ToolSettings.resolve("", "", kit, null, "", "").gradleSerial()).contains(kit);
        assertThat(ToolSettings.resolve(null, null, kit, null, null, null).gradleSerial()).as("a missing key is the default").contains(kit);
        assertThat(ToolSettings.resolve(" " + own + " ", "", kit, null, "", "").gradleSerial()).contains(own);
        assertThat(ToolSettings.resolve("none", "", kit, null, "", "").gradleSerial()).isEmpty();
        assertThat(ToolSettings.resolve("", "", dir.resolve("no-kit"), null, "", "").gradleSerial()).as("no kit yet: ./gradlew").isEmpty();
    }

    @Test
    void aWrapperThatIsNotExecutableRunsGradlewAndSaysSo(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("plain"), "x");

        ToolSettings s = ToolSettings.resolve(plain.toString(), "", dir.resolve("kit"), null, "", "tools.json: not JSON");

        assertThat(s.gradleSerial()).isEmpty();
        assertThat(s.problem()).isEqualTo("tools.json: not JSON\ntools.json: gradleWrapper " + plain + " is not an executable file; running ./gradlew");
    }

    @Test
    void jfrqIsTheUsersPathElseTheVariableElseTheFirstOnThePath(@TempDir Path dir) throws Exception {
        Path first = script(dir.resolve("a"), "jfrq");
        script(dir.resolve("b"), "jfrq");
        Files.createDirectories(dir.resolve("c/jfrq"));
        String path = dir.resolve("c") + File.pathSeparator + File.pathSeparator + first.getParent() + File.pathSeparator + dir.resolve("b");

        assertThat(ToolSettings.resolve("", "~/tools/jfrq", dir, "/env/jfrq", path, "").jfrq())
                .contains(Path.of(System.getProperty("user.home"), "tools/jfrq"));
        assertThat(ToolSettings.resolve("", "", dir, " /env/jfrq ", path, "").jfrq()).contains(Path.of("/env/jfrq"));
        assertThat(ToolSettings.resolve("", "", dir, "", path, "").jfrq()).as("a folder named jfrq is skipped").contains(first);
        assertThat(ToolSettings.resolve("", "", dir, null, null, "").jfrq()).isEqualTo(Optional.empty());
        assertThat(ToolSettings.resolve("", "", dir, null, "", "").javaHome()).isEqualTo(System.getProperty("java.home"));
    }
}
