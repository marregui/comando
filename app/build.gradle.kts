plugins {
    application
}

// Wiring, configuration, persistence and main.
dependencies {
    implementation(project(":core"))
    implementation(project(":db"))
    implementation(project(":claude"))
    implementation(project(":mcp"))
    implementation(project(":tools"))
    implementation(project(":ui"))
    implementation(libs.jackson.databind)
    implementation(libs.questdb)
    testImplementation(project(":testkit"))
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version, "Implementation-Title" to "comando")
    }
}

application {
    mainClass = "dev.marregui.comando.app.Main"
    applicationName = "comando"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Xms256m", "-Xmx4g", "-Dfile.encoding=UTF-8", "-Dapple.awt.application.name=comando",
        // AppKit reads the appearance when it starts, before any Java code of ours: comando's palette is dark only
        "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")
}

// Paints the window to PNG files while driving the app with intents: ./gradlew :app:snapshot -Pout=<dir> [-Plive=true] [-Phistory=<conversation id>] [-Ptools=true]
tasks.register<JavaExec>("snapshot") {
    group = "verification"
    description = "Drives comando with intents and paints its window to PNG files"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.marregui.comando.app.Snapshot"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcomando.prefsNode=dev/marregui/comando-snapshot")
    args(
        (project.findProperty("out") ?: layout.buildDirectory.dir("snapshots").get().asFile.path).toString(),
        (project.findProperty("live") ?: "false").toString(),
        (project.findProperty("history") ?: "").toString(),
        (project.findProperty("tools") ?: "false").toString()
    )
}

// Replays recorded intent logs against the Swing shell; needs a display (xvfb-run on Linux): ./gradlew :app:uiTest
val uiTest = tasks.register<Test>("uiTest") {
    group = "verification"
    description = "Replays intent logs against the Swing shell and checks the window against the final state"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*UiReplayTest") }
    systemProperty("java.awt.headless", "false")
    systemProperty("comando.uiTests", "true")
    systemProperty("comando.prefsNode", "dev/marregui/comando-test")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// An unsigned macOS disk image with its own Java runtime: ./gradlew :app:dmg (output in build/dmg).
// The modules are what jdeps reports for every jar, plus locale data, charsets and zipfs, which are
// reached through services and reflection rather than direct references.
tasks.register<Exec>("dmg") {
    group = "distribution"
    description = "Builds an unsigned macOS disk image with a bundled runtime"
    dependsOn(tasks.named("installDist"))
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val lib = layout.buildDirectory.dir("install/comando/lib")
    val out = layout.buildDirectory.dir("dmg")
    val appVersion = project.version.toString().substringBefore('-')
    val mainJar = "app-${project.version}.jar"
    inputs.dir(lib)
    outputs.dir(out)
    doFirst {
        delete(out)
        executable = jdk.get().metadata.installationPath.file("bin/jpackage").asFile.absolutePath
    }
    executable = "jpackage"
    args(
        "--type", "dmg",
        "--name", "comando",
        "--app-version", appVersion,
        "--vendor", "marregui",
        "--mac-package-identifier", "dev.marregui.comando",
        "--input", lib.get().asFile.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "dev.marregui.comando.app.Main",
        "--add-modules", "java.base,java.desktop,java.logging,java.naming,java.prefs,java.security.jgss,java.sql,jdk.httpserver,jdk.jfr," +
                "jdk.management,jdk.unsupported,jdk.localedata,jdk.charsets,jdk.zipfs",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Xms256m",
        "--java-options", "-Xmx4g",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dapple.awt.application.name=comando",
        "--java-options", "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
        "--dest", out.get().asFile.absolutePath
    )
}
