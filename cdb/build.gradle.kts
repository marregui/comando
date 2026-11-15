// cdb: comando's own columnar, time-partitioned database. JDK only; one public facade, dev.marregui.comando.cdb.Cdb.
dependencies {
    testImplementation(libs.archunit)
    testImplementation(libs.postgresql.cosa)
}

tasks.named<Test>("test") {
    // CdbArchitectureTest reads db's compiled classes from disk, so they must be current
    dependsOn(":db:classes")
    // the corpus of query shapes lives outside the repository; CorpusParseTest skips when it is absent
    System.getenv("CDB_CORPUS")?.let { systemProperty("cdb.corpus", it) }
    // StorePowerCutTest: CDB_POWERCUT_SEED replays a printed seed, CDB_POWERCUT_FULL=true runs every image it has
    System.getenv("CDB_POWERCUT_SEED")?.let { systemProperty("cdb.powercut.seed", it) }
    System.getenv("CDB_POWERCUT_FULL")?.let { systemProperty("cdb.powercut.full", it) }
    // StoreWalTest's differential: CDB_WAL_SEED replays a printed seed
    System.getenv("CDB_WAL_SEED")?.let { systemProperty("cdb.wal.seed", it) }
}

// ./gradlew :cdb:serve -Proot=<folder> -Pport=9100 [-PpgPort=8813] [-Pbind=0.0.0.0]: the shadow server beside QuestDB;
// pgPort adds the PostgreSQL wire, bind is the address of both (127.0.0.1 unless set)
tasks.register<JavaExec>("serve") {
    group = "application"
    description = "Serves a cdb folder over QuestDB's /exec and /write, and the PostgreSQL wire when -PpgPort is set"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.marregui.comando.cdb.CdbServe")
    args(
        (findProperty("root") ?: "${System.getProperty("user.home")}/.comando/cdb").toString(),
        (findProperty("port") ?: "9100").toString(),
    )
    findProperty("pgPort")?.let { args("--pg-port", it.toString()) }
    findProperty("bind")?.let { args("--bind", it.toString()) }
}
