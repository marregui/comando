import java.nio.file.Files

plugins {
    application
}

// A release builds with -PreleaseVersion=<the tag without its v>: the packages are named after it, and the jar's manifest
// carries it as the version comando shows. Without it, the version set for every module.
providers.gradleProperty("releaseVersion").orNull?.let { version = it }

// Wiring, configuration, persistence and main.
dependencies {
    implementation(project(":core"))
    implementation(project(":db"))
    implementation(project(":claude"))
    implementation(project(":mcp"))
    implementation(project(":tools"))
    implementation(project(":ui"))
    implementation(libs.questdb)
    testImplementation(project(":testkit"))
    // the keyboard tests reach into the editor and the terminal of a real window
    testImplementation(libs.rsyntaxtextarea)
    testImplementation(libs.jediterm.core)
    testImplementation(libs.jediterm.ui)
    testImplementation(libs.awaitility)
    testImplementation(libs.archunit)
    // the uiTest lock is a launcher session listener in the test sources
    testImplementation(libs.junit.launcher)
}

// the PostgreSQL driver brings checker-qual for its annotations only: nothing reads them at run time (record 157)
configurations.runtimeClasspath {
    exclude(group = "org.checkerframework", module = "checker-qual")
}

// no test of this module joins the comando Edge or the machine's QuestDB: a developer's own comando may be running them
// (records 123, 146)
tasks.withType<Test>().configureEach {
    systemProperty("comando.peers", "false")
    systemProperty("comando.questdb", "false")
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
        "-Dcomando.peers=false", "-Dcomando.questdb=false",
        // painted from its root pane, so the window opens on no screen and never takes the desktop's focus
        "-Dcomando.offScreen=true", "-Dapple.awt.UIElement=true")
    args(
        (project.findProperty("out") ?: layout.buildDirectory.dir("snapshots").get().asFile.path).toString(),
        (project.findProperty("live") ?: "false").toString(),
        (project.findProperty("history") ?: "").toString(),
        (project.findProperty("tools") ?: "false").toString()
    )
}

// The window replay's classes. CI splits them over several runners with -PuiShard=<k>/<n>: the k-th of n groups,
// balanced by greedy assignment, longest first, on the times in ci/ui-test-seconds.tsv for this system (a class not in
// it counts the median), so every runner of a system computes the same groups and each class runs in exactly one.
val uiTestClasses = listOf(
    "UiReplayTest",
    "UiKeysTest",
    "TreeClicksUiTest",
    "DarkPageTintUiTest",
    "ThreadBudgetTest",
    "CheckpointCompactUiTest",
    "CheckpointLockUiTest",
    "SessionCardUiTest",
    "LinksUiTest",
    "FocusBackUiTest",
    "OutlineUiTest",
    "LocateUiTest",
    "LowerDividerUiTest",
    "OwnLogUiTest",
    "ThemeFadeUiTest",
    "OneIconSetUiTest",
    "PanelMenusIconsUiTest",
    "LayoutKeeperUiTest",
    "FocusRouterUiTest",
    "QuitTeardownUiTest",
    "WorkListUiTest",
    "DiffThemeUiTest",
    "ConsoleStyleUiTest",
    "AttentionUiTest",
    "ReportFirstUiTest",
    "SubjectUiTest",
    "SearchUiTest",
    "PartitionUiTest",
    "CompareKeysUiTest",
    "QuestionUnansweredUiTest",
    "DeliverablesUiTest",
    "TranscriptAboveUiTest",
    "TranscriptFollowUiTest",
    "TerminalToolsUiTest",
    "WidgetMenusIconsUiTest",
    "SessionsMultiSelectUiTest",
    "SessionBarButtonsUiTest",
)

fun uiShard(spec: String, seconds: String, mac: Boolean): List<String> {
    val (index, count) = spec.split("/").map(String::toInt)
    require(count >= 1 && index in 1..count) { "uiShard is <k>/<n> with 1 <= k <= n, not $spec" }
    val measured = seconds.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
        val cells = line.split("\t")
        cells[0] to cells[if (mac) 1 else 2].toDouble()
    }
    val known = uiTestClasses.mapNotNull { measured[it] }.sorted()
    val median = if (known.isEmpty()) 1.0 else known[known.size / 2]
    val loads = DoubleArray(count)
    val shards = List(count) { mutableListOf<String>() }
    for (name in uiTestClasses.sortedWith(compareBy<String>({ -(measured[it] ?: median) }, { it }))) {
        val lightest = loads.indices.minBy { loads[it] }
        loads[lightest] += measured[name] ?: median
        shards[lightest] += name
    }
    return shards[index - 1]
}

// Replays recorded intent logs against the Swing shell; needs a display (xvfb-run on Linux): ./gradlew :app:uiTest
val uiTest = tasks.register<Test>("uiTest") {
    group = "verification"
    description = "Replays intent logs against the Swing shell and checks the window against the final state"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    // CI's shard (-PuiShard=<k>/<n>), else every class; by class file, not by a filter: with forkEvery a filter still
    // starts a JVM for every test class to drop it there
    val shard = providers.gradleProperty("uiShard").orNull
    val classes = if (shard == null) uiTestClasses else uiShard(shard,
        providers.fileContents(rootProject.layout.projectDirectory.file("ci/ui-test-seconds.tsv")).asText.get(),
        providers.systemProperty("os.name").get().startsWith("Mac"))
    include(classes.map { "**/$it.class" })
    // one JVM per class: each class starts from a fresh AWT state, whichever window the desktop put in front last
    forkEvery = 1
    systemProperty("java.awt.headless", "false")
    // also switches on the machine-wide lock (uilock.UiTestLock): each JVM of this task waits for any other uiTest JVM on
    // the machine, of any checkout or user, to end
    systemProperty("comando.uiTests", "true")
    // ThemeFadeUiTest writes the window in the light theme to this PNG when it is given: -Pcomando.lightShot=<file>
    providers.gradleProperty("comando.lightShot").orNull?.let { systemProperty("comando.lightShot", it) }
    // the windows open on no screen, without a Dock icon, and never take the desktop's focus
    systemProperty("comando.offScreen", "true")
    systemProperty("apple.awt.UIElement", "true")
    systemProperty("comando.prefsNode", "dev/marregui/comando-test")
    // no window test reads the user's real Codex plan: the status bar would change under a test at the read's return
    environment("COMANDO_CODEX", layout.buildDirectory.file("no-codex").get().asFile.path)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
    // never from the build cache: what it checks is the desktop and the windows it opens, which are no input
    outputs.cacheIf { false }
    // and never up to date: a second run without changes still opens the windows and checks them
    outputs.upToDateWhen { false }
}


// The operating system and architecture this build runs on, as the packages' names say them: macos, linux or windows;
// arm64 or x64.
val platform = providers.systemProperty("os.name").get().lowercase().let {
    when {
        it.contains("mac") -> "macos"
        it.contains("windows") -> "windows"
        else -> "linux"
    }
}
val arch = providers.systemProperty("os.arch").get().let {
    when (it) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> it
    }
}

// What every package of comando carries: its own Java runtime of the modules jdeps reports for every jar, plus locale
// data, charsets and zipfs, which are reached through services and reflection rather than direct references; and the
// JVM options of applicationDefaultJvmArgs. jdk.jfr, jdk.management.jfr, jdk.management.agent: no comando code uses
// them; they let the packaged app be recorded, and watched over JMX, like any JVM.
// The icon is src/main/icon/comando.icns on macOS and ui/src/main/resources/icons/comando.png on Linux, both made from
// src/main/icon/comando.svg; Windows packages carry jpackage's own icon. To redraw both after editing the SVG:
//   rsvg-convert -w 1024 -h 1024 app/src/main/icon/comando.svg -o ui/src/main/resources/icons/comando.png
//   mkdir -p /tmp/comando.iconset && for s in 16 32 128 256 512; do
//     sips -z $s $s ui/src/main/resources/icons/comando.png --out /tmp/comando.iconset/icon_${s}x${s}.png
//     sips -z $((s*2)) $((s*2)) ui/src/main/resources/icons/comando.png --out /tmp/comando.iconset/icon_${s}x${s}@2x.png
//   done && iconutil -c icns /tmp/comando.iconset -o app/src/main/icon/comando.icns
fun jpackage(name: String, type: String, folder: String, summary: String) = tasks.register<Exec>(name) {
    group = "distribution"
    description = summary
    dependsOn(tasks.named("installDist"))
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val tool = if (platform == "windows") "bin/jpackage.exe" else "bin/jpackage"
    val lib = layout.buildDirectory.dir("install/comando/lib")
    val out = layout.buildDirectory.dir(folder)
    inputs.dir(lib)
    outputs.dir(out)
    doFirst {
        // the file's own delete: the script's delete() is a script object, which the configuration cache cannot keep
        out.get().asFile.deleteRecursively()
        executable = jdk.get().metadata.installationPath.file(tool).asFile.absolutePath
    }
    executable = "jpackage"
    args(
        "--type", type,
        "--name", "comando",
        "--app-version", project.version.toString().substringBefore('-'),
        "--vendor", "marregui",
        "--input", lib.get().asFile.absolutePath,
        "--main-jar", "app-${project.version}.jar",
        "--main-class", "dev.marregui.comando.app.Main",
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
    when (platform) {
        "macos" -> args("--icon", layout.projectDirectory.file("src/main/icon/comando.icns").asFile.absolutePath,
            "--mac-package-identifier", "dev.marregui.comando")
        "linux" -> args("--icon", rootProject.layout.projectDirectory.file("ui/src/main/resources/icons/comando.png").asFile.absolutePath)
    }
}

// An unsigned macOS disk image: ./gradlew :app:dmg (output in build/dmg, named comando-<version>.dmg).
val dmg = jpackage("dmg", "dmg", "dmg", "Builds an unsigned macOS disk image with a bundled runtime")

// The application as a folder, for this system: ./gradlew :app:appImage (output in build/app-image: comando.app on
// macOS, comando/ elsewhere).
val appImage = jpackage("appImage", "app-image", "app-image", "Builds the application folder with a bundled runtime")

// The file a release publishes for this system: ./gradlew :app:package (output in build/package). macOS: the disk
// image, comando-<version>-macos-<arch>.dmg; Linux: the application folder as comando-<version>-linux-<arch>.tar.gz;
// Windows: as comando-<version>-windows-<arch>.zip. Each archive holds one folder, comando/. Nothing is signed.
val packageName = "comando-${project.version}-$platform-$arch"
when (platform) {
    "macos" -> tasks.register<Sync>("package") {
        group = "distribution"
        description = "Builds this system's release file: the disk image"
        val file = "$packageName.dmg"
        from(dmg) {
            include("*.dmg")
            rename { file }
        }
        into(layout.buildDirectory.dir("package"))
    }
    "windows" -> tasks.register<Zip>("package") {
        group = "distribution"
        description = "Builds this system's release file: the application folder as a zip archive"
        from(appImage)
        destinationDirectory = layout.buildDirectory.dir("package")
        archiveFileName = "$packageName.zip"
    }
    else -> tasks.register<Tar>("package") {
        group = "distribution"
        description = "Builds this system's release file: the application folder as a gzipped tar archive"
        from(appImage)
        compression = Compression.GZIP
        destinationDirectory = layout.buildDirectory.dir("package")
        archiveFileName = "$packageName.tar.gz"
    }
}
