// cdb: comando's own columnar, time-partitioned database. JDK only; one public facade, dev.marregui.comando.cdb.Cdb.
dependencies {
    testImplementation(libs.archunit)
}

tasks.named<Test>("test") {
    // CdbArchitectureTest reads db's compiled classes from disk, so they must be current
    dependsOn(":db:classes")
    // the corpus of query shapes lives outside the repository; CorpusParseTest skips when it is absent
    System.getenv("CDB_CORPUS")?.let { systemProperty("cdb.corpus", it) }
}

// ./gradlew :cdb:serve -Proot=<folder> -Pport=9100: the shadow server beside QuestDB
tasks.register<JavaExec>("serve") {
    group = "application"
    description = "Serves a cdb folder over QuestDB's /exec and /write on 127.0.0.1"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.marregui.comando.cdb.CdbServe")
    args(
        (findProperty("root") ?: "${System.getProperty("user.home")}/.comando/cdb").toString(),
        (findProperty("port") ?: "9100").toString(),
    )
}
