// Claude Code adapter: the headless CLI process, the stream-JSON mapping, the session catalogue.
dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
    testImplementation(project(":testkit"))
    testImplementation(libs.awaitility)
}

// The Claude Code kit (the repository's kit/ folder) travels in the jar, with an index of its files: a jar cannot list
// a folder, and the kit has hidden ones (.claude-plugin).
val kitDir = rootProject.file("kit")
val kitIndex = tasks.register("kitIndex") {
    val out = layout.buildDirectory.file("generated/kit/dev/marregui/comando/claude/kit/files.txt")
    inputs.dir(kitDir)
    outputs.file(out)
    doLast {
        val files = kitDir.walkTopDown().filter { it.isFile && it.name != ".DS_Store" }
            .map { it.relativeTo(kitDir).invariantSeparatorsPath }.sorted().toList()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(files.joinToString("\n", postfix = "\n"))
    }
}
tasks.processResources {
    dependsOn(kitIndex)
    from(kitDir) {
        into("dev/marregui/comando/claude/kit")
        exclude("**/.DS_Store")
    }
    from(layout.buildDirectory.dir("generated/kit"))
}
