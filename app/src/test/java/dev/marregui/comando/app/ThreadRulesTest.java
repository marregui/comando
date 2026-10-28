package dev.marregui.comando.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.loop.Watchdog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.BaseStream;
import java.util.stream.StreamSupport;

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
/// - nothing runs on the JDK's shared fork-join pool
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

    /// No class runs work on the JDK's shared fork-join pool: no `parallelStream`, no `parallel()` on a stream, no
    /// parallel `StreamSupport` stream, no `Arrays.parallel*`, no `ForkJoinPool` or `ForkJoinTask` call, and no
    /// `CompletableFuture` `*Async` call without an executor of its own, which would run on that pool. Its workers
    /// belong to no kind and no owner; work spread over the cores goes to forks from [Threads].
    @Test
    void nothingRunsOnTheSharedForkJoinPool() {
        noClasses().should().callMethodWhere(target(name("parallelStream")).and(target(owner(assignableTo(Collection.class)))))
                .orShould().callMethodWhere(target(name("parallel")).and(target(owner(assignableTo(BaseStream.class)))))
                .orShould().callMethodWhere(target(owner(assignableTo(StreamSupport.class))).and(lastParameterIs(boolean.class)))
                .orShould().callMethodWhere(target(owner(assignableTo(Arrays.class))).and(nameStartsWith("parallel")))
                .orShould().callMethodWhere(target(owner(assignableTo(ForkJoinPool.class))))
                .orShould().callMethodWhere(target(owner(assignableTo(ForkJoinTask.class))))
                .orShould().callMethodWhere(target(owner(assignableTo(CompletableFuture.class))).and(nameEndsWith("Async"))
                        .and(lastParameterIs(Executor.class).negate()))
                .check(comando);
    }

    private static DescribedPredicate<JavaCall<?>> nameStartsWith(String prefix) {
        return DescribedPredicate.describe("name starting with " + prefix, call -> call.getName().startsWith(prefix));
    }

    private static DescribedPredicate<JavaCall<?>> nameEndsWith(String suffix) {
        return DescribedPredicate.describe("name ending with " + suffix, call -> call.getName().endsWith(suffix));
    }

    private static DescribedPredicate<JavaCall<?>> lastParameterIs(Class<?> type) {
        return DescribedPredicate.describe("last parameter " + type.getSimpleName(), call -> {
            List<JavaClass> params = call.getTarget().getRawParameterTypes();
            return !params.isEmpty() && params.getLast().isEquivalentTo(type);
        });
    }
}
