// MCP server on localhost: UI tools, the approval tool, database tools, the audit log.
dependencies {
    api(project(":core"))
    testImplementation(libs.awaitility)
    testImplementation(project(":testkit"))
}
