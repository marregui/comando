package dev.marregui.comando.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.loop.Watchdog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Timer;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// The thread rules of `docs/architecture.md` §6, checked over every main class of comando: a platform thread only where
/// a kind needs one, every other thread from [Threads], so a new part cannot add an operating-system thread or an
/// unowned one without changing this test.
/// - only the reducer, the watchdog, a session's stdout reader and quitting start platform threads
/// - virtual threads, executors, thread pools and `java.util.Timer` come only from the thread kit
class ThreadRulesTest {

    /** Package-private in {@code claude}: named, not referenced. */
    private static final String CLAUDE_PROCESS = "dev.marregui.comando.claude.ClaudeProcess";
    private static JavaClasses comando;

    @BeforeAll
    static void load() {
        comando = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.marregui.comando");
    }

    /// No class but [StateLoop], [Watchdog], `ClaudeProcess` and [Main] calls `Thread.ofPlatform` or constructs a `Thread`.
    @Test
    void platformThreadsComeOnlyFromTheKindsThatNeedThem() {
        noClasses().that().doNotHaveFullyQualifiedName(StateLoop.class.getName())
                .and().doNotHaveFullyQualifiedName(Watchdog.class.getName())
                .and().doNotHaveFullyQualifiedName(CLAUDE_PROCESS)
                .and().doNotHaveFullyQualifiedName(Main.class.getName())
                .should().callMethod(Thread.class, "ofPlatform")
                .orShould().callConstructorWhere(target(owner(assignableTo(Thread.class))))
                .check(comando);
    }

    /// No class but [Threads] calls `Thread.ofVirtual`, an `Executors` factory other than `newVirtualThreadPerTaskExecutor`,
    /// or constructs a `ThreadPoolExecutor` or a `java.util.Timer`.
    @Test
    void virtualThreadsAndExecutorsComeFromTheThreadKit() {
        noClasses().that().doNotHaveFullyQualifiedName(Threads.class.getName())
                .should().callMethod(Thread.class, "ofVirtual")
                .orShould().callMethodWhere(target(owner(assignableTo(Executors.class))).and(target(name("newVirtualThreadPerTaskExecutor")).negate()))
                .orShould().callConstructorWhere(target(owner(assignableTo(ThreadPoolExecutor.class))))
                .orShould().callConstructorWhere(target(owner(assignableTo(Timer.class))))
                .check(comando);
    }
}
