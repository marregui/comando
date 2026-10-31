// Swing shell, panels, dialogs, the widget kit and the widgets.
dependencies {
    api(project(":core"))
    implementation(libs.flatlaf)
    implementation(libs.flatlaf.extras)
    implementation(libs.jsvg)
    implementation(libs.rsyntaxtextarea)
    implementation(libs.autocomplete)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.diffutils)
    implementation(libs.jackson.databind)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.pty4j)
    runtimeOnly(libs.slf4j.jdk14)
    testImplementation(project(":testkit"))
    testImplementation(libs.archunit)
    testImplementation(libs.awaitility)
}

// Help ▸ Tutorial shows the tutorial from the docs, so the app and the docs never disagree.
tasks.processResources {
    from(rootProject.file("docs/tutorial.md")) {
        into("dev/marregui/comando/ui/help")
    }
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
