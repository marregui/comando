// Conventions shared by every module: JDK 25 toolchain, strict compilation, JUnit Platform,
// and tests that never touch the user's data root.
subprojects {
    apply(plugin = "java-library")

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
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing,-this-escape", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
        // QuestDB 10's workers run on the JDK's internal continuations
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
        systemProperty("comando.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
        // opt-in: the keychain test writes and deletes one item in the login keychain
        systemProperty("comando.keychainTest", providers.gradleProperty("keychainTest").getOrElse("false"))
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
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
