// Conventions shared by every module: JDK 25 toolchain, strict compilation, JUnit Platform,
// and tests that never touch the user's data root.
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")

    group = "dev.marregui.comando"
    version = "2.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        // JediTerm, the terminal emulator, is published only to JetBrains' repository; nothing else comes from it
        exclusiveContent {
            forRepository {
                maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
            }
            filter {
                includeGroup("org.jetbrains.jediterm")
            }
        }
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        // every lint as an error (G-10.10), this-escape included (G-9.5); serial excluded, as Swing components are
        // Serializable and comando never serializes one
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
    }

    // the checks of the Java Coding Standard javac cannot make (G-10.2 final, G-10.8 braces, G-10.11 imports), on
    // main and test code; any report fails the build
    extensions.configure<CheckstyleExtension> {
        toolVersion = libs.findVersion("checkstyle").get().requiredVersion
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        configProperties = mapOf("projectPackagePattern" to "^dev\\.marregui\\.comando\\.")
        maxWarnings = 0
        isIgnoreFailures = false
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
        // QuestDB 10's workers run on the JDK's internal continuations
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
        systemProperty("comando.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
        // opt-in: the keychain test writes and deletes one item in the login keychain
        systemProperty("comando.keychainTest", providers.gradleProperty("keychainTest").getOrElse("false"))
        // opt-in: the Edge live test downloads the pinned HiveMQ Edge release (359 MB) and runs it on free loopback ports;
        // -PedgeLiveCache=<folder> keeps the zip between runs
        systemProperty("comando.edgeLiveTest", providers.gradleProperty("edgeLiveTest").getOrElse("false"))
        providers.gradleProperty("edgeLiveCache").orNull?.let { systemProperty("comando.edgeLiveCache", it) }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // a module's unit tests in up to three JVMs, while the other modules run theirs (gradle.properties);
    // :app:uiTest keeps one JVM at a time, as its windows share one desktop
    tasks.named<Test>("test") {
        maxParallelForks = 3
    }

    dependencies {
        "compileOnly"(libs.findLibrary("annotations").get())
        "testCompileOnly"(libs.findLibrary("annotations").get())
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findLibrary("assertj").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }
}
