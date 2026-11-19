// frontear: comando's own MQTT 5 broker, client and mesh link, the instances' message fabric. JDK and core only.
plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    testFixturesImplementation(project(":core"))
    testImplementation(project(":testkit"))
    testImplementation(libs.awaitility)
}

// LinkLogBench: -PfrontearBench=true prints the link log's durable-append latency and throughput
tasks.named<Test>("test") {
    providers.gradleProperty("frontearBench").orNull?.let { systemProperty("frontear.bench", it) }
}
