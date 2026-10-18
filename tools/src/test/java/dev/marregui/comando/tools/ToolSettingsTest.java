package dev.marregui.comando.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSettingsTest {

    @Test
    void gradleRunsThroughTheKitsWrapperWhenItIsThereElseGradlew(@TempDir Path dir) throws Exception {
        Path kit = Files.writeString(Files.createDirectories(dir.resolve("kit/bin")).resolve("gradle-serial"), "#!/bin/sh\n");

        assertThat(ToolSettings.of(kit).gradleSerial()).as("not executable: ./gradlew").isEmpty();
        Files.setPosixFilePermissions(kit, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(ToolSettings.of(kit).gradleSerial()).contains(kit);
        assertThat(ToolSettings.of(dir.resolve("no-kit")).gradleSerial()).as("no kit yet: ./gradlew").isEmpty();
    }

    @Test
    void childProcessesGetTheJdkThatRunsComando(@TempDir Path dir) {
        assertThat(ToolSettings.of(dir).javaHome()).isEqualTo(System.getProperty("java.home"));
    }
}
