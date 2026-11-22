// The classifier of this build's JavaFX jars, from the system it runs on.
val javafxPlatform: String
    get() {
        val os = providers.systemProperty("os.name").get().lowercase()
        val arm = providers.systemProperty("os.arch").get() in listOf("aarch64", "arm64")
        return when {
            os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
            os.contains("windows") -> "win"
            else -> if (arm) "linux-aarch64" else "linux"
        }
    }

// Swing shell, panels, dialogs, the widget kit and the widgets.
dependencies {
    api(project(":core"))
    implementation(libs.flatlaf)
    implementation(libs.jsvg)
    implementation(libs.rsyntaxtextarea)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.pty4j)
    compileOnly(libs.annotations)
    runtimeOnly(libs.slf4j.jdk14)
    testImplementation(project(":testkit"))
    testImplementation(libs.archunit)
    testImplementation(libs.awaitility)
    // JavaFX's WebView renders HTML pages in panels (record 2), on the class path as comando has no module-info. Its
    // jars carry native code, one per platform by classifier; their POMs name the platform through Maven profiles
    // Gradle does not read, so each jar is taken alone. netscape.javascript is JDK 25's own jdk.jsobject.
    for (javafx in listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.controls, libs.javafx.media,
            libs.javafx.web, libs.javafx.swing)) {
        implementation(variantOf(javafx) { classifier(javafxPlatform) }) {
            isTransitive = false
        }
    }
}

// Help ▸ Tutorial shows the tutorial from the docs, so the app and the docs never disagree.
tasks.processResources {
    from(rootProject.file("docs/tutorial.md")) {
        into("dev/marregui/comando/ui/help")
    }
}

// TestDocsTest reads every module's test sources, so it runs in a task of its own with them as inputs: a comment
// removed in another module runs it again instead of reusing an earlier pass, and the rest of this module's tests keep
// only their own inputs. By class file, not by a filter, so neither task forks a JVM for the other's classes.
val testDocsClass = "dev/marregui/comando/ui/shell/TestDocsTest.class"
val testDocs = tasks.register<Test>("testDocs") {
    group = "verification"
    description = "Checks that every module's test classes and test methods carry a /// comment."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include(testDocsClass)
    inputs.files(rootProject.fileTree(rootDir) { include("*/src/test/java/**/*Test.java") })
        .withPropertyName("testSourcesOfEveryModule")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.test {
    exclude(testDocsClass)
}
tasks.check {
    dependsOn(testDocs)
}

// The transcript renderer's frame-time gate: ./gradlew :ui:transcriptGate
tasks.register<JavaExec>("transcriptGate") {
    group = "verification"
    description = "Measures transcript frame times on a synthetic streamed session; fails when p99 exceeds 16 ms."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.marregui.comando.ui.widgets.transcript.render.TranscriptGate")
    jvmArgs("-Djava.awt.headless=true")
}

// The grid's frame-time gate: ./gradlew :ui:gridGate; it measures and prints HOT when p99 exceeds 4 ms.
tasks.register<JavaExec>("gridGate") {
    group = "verification"
    description = "Measures grid frame times over 100 000 rows; prints HOT when p99 exceeds 4 ms."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.marregui.comando.ui.widgets.table.GridGate")
    jvmArgs("-Djava.awt.headless=true")
}

// The plot's frame-time gate: ./gradlew :ui:plotGate; it measures and prints HOT when p99 exceeds 4 ms.
tasks.register<JavaExec>("plotGate") {
    group = "verification"
    description = "Measures plot frame times over 4 series of 200 000 points; prints HOT when p99 exceeds 4 ms."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.marregui.comando.ui.widgets.plot.PlotGate")
    jvmArgs("-Djava.awt.headless=true")
}
