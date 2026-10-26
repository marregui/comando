// Claude Code adapter: the headless CLI process, the stream-JSON mapping, the session catalogue.
dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
    testImplementation(project(":testkit"))
    testImplementation(libs.awaitility)
}

