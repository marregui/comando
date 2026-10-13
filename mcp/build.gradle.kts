// MCP server on localhost: UI tools, the approval tool, database tools, the audit log.
dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
    testImplementation(project(":testkit"))
}
