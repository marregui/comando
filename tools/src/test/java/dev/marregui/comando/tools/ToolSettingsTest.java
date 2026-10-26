package dev.marregui.comando.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSettingsTest {

    @Test
    void gradleRunsThroughTheWrapperOnThePathWhenItIsThereElseGradlew(@TempDir Path dir) throws Exception {
        Path first = Files.createDirectories(dir.resolve("first"));
        Path second = Files.createDirectories(dir.resolve("second"));
        Path notRunnable = Files.writeString(first.resolve("gradle-serial"), "#!/bin/sh\n");
        Path serial = Files.writeString(second.resolve("gradle-serial"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(serial, PosixFilePermissions.fromString("rwxr-xr-x"));
        String path = String.join(File.pathSeparator, "", dir.resolve("none").toString(), first.toString(), second.toString());

        assertThat(ToolSettings.of(path).gradleSerial()).as("the first that runs").contains(serial);
        Files.setPosixFilePermissions(notRunnable, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(ToolSettings.of(path).gradleSerial()).as("in PATH order").contains(notRunnable);
        assertThat(ToolSettings.of(dir.resolve("none").toString()).gradleSerial()).as("none: ./gradlew").isEmpty();
        assertThat(ToolSettings.of(null).gradleSerial()).isEmpty();
    }

    @Test
    void childProcessesGetTheJdkThatRunsComando() {
        assertThat(ToolSettings.of("").javaHome()).isEqualTo(System.getProperty("java.home"));
    }
}
