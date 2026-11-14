// Claude Code adapter: the headless CLI process, the stream-JSON mapping, the session catalogue.
dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
    testImplementation(libs.awaitility)
}

