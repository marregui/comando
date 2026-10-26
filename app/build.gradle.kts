import java.nio.file.Files

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
    // the keyboard tests reach into the editor and the terminal of a real window
    testImplementation(libs.rsyntaxtextarea)
    testImplementation(libs.jediterm.core)
    testImplementation(libs.jediterm.ui)
    testImplementation(libs.awaitility)
    testImplementation(libs.archunit)
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version, "Implementation-Title" to "comando")
    }
}

application {
    mainClass = "dev.marregui.comando.app.Main"
    applicationName = "comando"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-Dapple.awt.application.name=comando",
        // QuestDB 10's workers run on the JDK's internal continuations
        "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
        // the live heap is about 50 MB: start small, and let G1 give back what an idle minute leaves unused
        "-Xms64m", "-Xmx4g", "-XX:G1PeriodicGCInterval=60000",
        // AppKit reads the appearance when it starts, before any Java code of ours: comando's palette is dark only
        "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")
}

// ./gradlew :app:run starts comando from a copy of this checkout's build output made at launch. A build while comando
// runs rewrites the module jars and class files in place, and the running JVM loads classes from them lazily: the
// window then fails on the first class it loads afterwards. Each launch copies to a folder of its own; a copy is
// deleted at a later launch once no live process names it on its command line and it is two minutes old, so a launch
// still copying or starting its JVM keeps its copy. The jars from Gradle's cache are used where they are, builds
// never rewrite them. The copies live under .gradle/, which clean leaves alone.
tasks.named<JavaExec>("run") {
    val checkout = rootDir
    val stages = rootDir.resolve(".gradle/run-stage")
    val objects = objects
    doFirst {
        val live = ProcessHandle.allProcesses().map { it.info().commandLine().orElse("") }.toList().joinToString("\n")
        val young = System.currentTimeMillis() - 120_000
        stages.listFiles()?.filter { !live.contains(it.absolutePath + File.separator) && it.lastModified() < young }
            ?.forEach { it.deleteRecursively() }
        stages.mkdirs()
        val stage = Files.createTempDirectory(stages.toPath(), "stage-").toFile()
        classpath = objects.fileCollection().from(classpath.files.mapIndexed { i, f ->
            if (f.exists() && f.startsWith(checkout)) stage.resolve("$i-${f.name}").also { f.copyRecursively(it) } else f
        })
    }
}

// Paints the window to PNG files while driving the app with intents: ./gradlew :app:snapshot -Pout=<dir> [-Plive=true] [-Phistory=<conversation id>] [-Ptools=true]
tasks.register<JavaExec>("snapshot") {
    group = "verification"
    description = "Drives comando with intents and paints its window to PNG files"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.marregui.comando.app.Snapshot"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED", "-Dcomando.prefsNode=dev/marregui/comando-snapshot",
        // painted from its root pane, so the window opens on no screen and never takes the desktop's focus
        "-Dcomando.offScreen=true", "-Dapple.awt.UIElement=true")
    args(
        (project.findProperty("out") ?: layout.buildDirectory.dir("snapshots").get().asFile.path).toString(),
        (project.findProperty("live") ?: "false").toString(),
        (project.findProperty("history") ?: "").toString(),
        (project.findProperty("tools") ?: "false").toString()
    )
}

// Paints the README's picture: QuestDB started and connected, a real haiku turn that queries and plots: ./gradlew :app:hero -Pout=<dir>
tasks.register<JavaExec>("hero") {
    group = "verification"
    description = "Paints comando at work, with a live Claude Code turn, to hero.png"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.marregui.comando.app.Hero"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED", "-Dcomando.prefsNode=dev/marregui/comando-hero",
        // painted from its root pane, so the window opens on no screen and never takes the desktop's focus
        "-Dcomando.offScreen=true", "-Dapple.awt.UIElement=true")
    args((project.findProperty("out") ?: layout.buildDirectory.dir("snapshots").get().asFile.path).toString())
}

// Replays recorded intent logs against the Swing shell; needs a display (xvfb-run on Linux): ./gradlew :app:uiTest
val uiTest = tasks.register<Test>("uiTest") {
    group = "verification"
    description = "Replays intent logs against the Swing shell and checks the window against the final state"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("*UiReplayTest")
        includeTestsMatching("*UiKeysTest")
        includeTestsMatching("*TreeClicksUiTest")
        includeTestsMatching("*DarkPageGoldUiTest")
        includeTestsMatching("*ThreadBudgetTest")
        includeTestsMatching("*CheckpointClearUiTest")
        includeTestsMatching("*SessionCardUiTest")
    }
    // one JVM per class: each class starts from a fresh AWT state, whichever window the desktop put in front last
    forkEvery = 1
    systemProperty("java.awt.headless", "false")
    systemProperty("comando.uiTests", "true")
    // the windows open on no screen, without a Dock icon, and never take the desktop's focus
    systemProperty("comando.offScreen", "true")
    systemProperty("apple.awt.UIElement", "true")
    systemProperty("comando.prefsNode", "dev/marregui/comando-test")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
}

// An unsigned macOS disk image with its own Java runtime: ./gradlew :app:dmg (output in build/dmg).
// The modules are what jdeps reports for every jar, plus locale data, charsets and zipfs, which are
// reached through services and reflection rather than direct references.
// The icon is src/main/icon/comando.icns, made from comando.svg; the window and Dock use the same picture as
// ui/src/main/resources/icons/comando.png. To redraw both after editing the SVG:
//   rsvg-convert -w 1024 -h 1024 app/src/main/icon/comando.svg -o ui/src/main/resources/icons/comando.png
//   mkdir -p /tmp/comando.iconset && for s in 16 32 128 256 512; do
//     sips -z $s $s ui/src/main/resources/icons/comando.png --out /tmp/comando.iconset/icon_${s}x${s}.png
//     sips -z $((s*2)) $((s*2)) ui/src/main/resources/icons/comando.png --out /tmp/comando.iconset/icon_${s}x${s}@2x.png
//   done && iconutil -c icns /tmp/comando.iconset -o app/src/main/icon/comando.icns
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
        // the file's own delete: the script's delete() is a script object, which the configuration cache cannot keep
        out.get().asFile.deleteRecursively()
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
        "--icon", layout.projectDirectory.file("src/main/icon/comando.icns").asFile.absolutePath,
        // jdk.jfr, jdk.management.jfr, jdk.management.agent: no comando code uses them; they let the packaged app be
        // recorded, and watched over JMX, like any JVM
        "--add-modules", "java.base,java.desktop,java.logging,java.naming,java.prefs,java.security.jgss,java.sql,jdk.httpserver,jdk.jfr," +
                "jdk.management,jdk.management.jfr,jdk.management.agent,jdk.unsupported,jdk.localedata,jdk.charsets,jdk.zipfs",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
        "--java-options", "-Xms64m",
        "--java-options", "-Xmx4g",
        "--java-options", "-XX:G1PeriodicGCInterval=60000",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dapple.awt.application.name=comando",
        "--java-options", "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
        "--dest", out.get().asFile.absolutePath
    )
}
