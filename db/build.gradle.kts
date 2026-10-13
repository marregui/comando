// JDBC adapter: connection lanes, the executor with request ids and cancel, columnar results.
dependencies {
    api(project(":core"))
    implementation(libs.postgresql)
    testImplementation(project(":testkit"))
    testImplementation(libs.questdb)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.slf4j.jdk14)
}
