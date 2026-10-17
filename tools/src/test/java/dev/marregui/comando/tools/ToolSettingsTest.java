package dev.marregui.comando.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

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

        assertThat(ToolSettings.resolve("", kit, "").gradleSerial()).contains(kit);
        assertThat(ToolSettings.resolve(null, kit, null).gradleSerial()).as("a missing key is the default").contains(kit);
        assertThat(ToolSettings.resolve(" " + own + " ", kit, "").gradleSerial()).contains(own);
        assertThat(ToolSettings.resolve("none", kit, "").gradleSerial()).isEmpty();
        assertThat(ToolSettings.resolve("", dir.resolve("no-kit"), "").gradleSerial()).as("no kit yet: ./gradlew").isEmpty();
    }

    @Test
    void aWrapperThatIsNotExecutableRunsGradlewAndSaysSo(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("plain"), "x");

        ToolSettings s = ToolSettings.resolve(plain.toString(), dir.resolve("kit"), "tools.json: not JSON");

        assertThat(s.gradleSerial()).isEmpty();
        assertThat(s.problem()).isEqualTo("tools.json: not JSON\ntools.json: gradleWrapper " + plain + " is not an executable file; running ./gradlew");
    }

    @Test
    void childProcessesGetTheJdkThatRunsComando(@TempDir Path dir) {
        assertThat(ToolSettings.resolve("", dir, "").javaHome()).isEqualTo(System.getProperty("java.home"));
    }
}
