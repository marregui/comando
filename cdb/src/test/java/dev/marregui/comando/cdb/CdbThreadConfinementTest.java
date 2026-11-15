package dev.marregui.comando.cdb;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Only the classes that own threads create them: the launcher's shutdown hook, the HTTP and PostgreSQL servers'
/// connection threads, and each store's applier. Read from the bytecode of every main class of the module, nested and
/// lambda bodies included: a `new Thread`, a `Thread.ofPlatform`, `Thread.ofVirtual` or `Thread.startVirtualThread`
/// call, or any `Executors.new*` factory, anywhere else fails the test.
class CdbThreadConfinementTest {

    // a class and its nested classes, by binary name prefix
    private static final Set<String> OWNERS = Set.of(
            "dev/marregui/comando/cdb/CdbServe",
            "dev/marregui/comando/cdb/serve/CdbServer",
            "dev/marregui/comando/cdb/serve/PgServer",
            "dev/marregui/comando/cdb/store/Applier");

    /// No main class outside the four owners creates a thread or an executor, and the four owners are found.
    @Test
    void onlyTheOwnersCreateThreads() throws IOException, URISyntaxException {
        final Path classes = Path.of(Cdb.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final List<String> offenders = new ArrayList<>();
        final List<String> creators = new ArrayList<>();
        try (final Stream<Path> files = Files.walk(classes)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                final ClassModel model = ClassFile.of().parse(file);
                final String owner = model.thisClass().asInternalName();
                for (final MethodModel method : model.methods()) {
                    method.code().ifPresent(code -> code.forEach(element -> {
                        final String created = created(element);
                        if (created != null) {
                            final String site = owner + '.' + method.methodName().stringValue() + ": " + created;
                            (isOwner(owner) ? creators : offenders).add(site);
                        }
                    }));
                }
            }
        }
        assertThat(offenders).as("threads created outside their owners").isEmpty();
        assertThat(creators).as("the owners' thread creation, read from their bytecode").isNotEmpty();
    }

    // the thread or executor an instruction creates, null when none
    private static String created(final Object element) {
        if (element instanceof final NewObjectInstruction create
                && create.className().asInternalName().equals("java/lang/Thread")) {
            return "new Thread";
        }
        if (element instanceof final InvokeInstruction invoke) {
            final String type = invoke.owner().asInternalName();
            final String name = invoke.name().stringValue();
            if (type.equals("java/lang/Thread")
                    && (name.equals("ofPlatform") || name.equals("ofVirtual") || name.equals("startVirtualThread"))) {
                return "Thread." + name;
            }
            if (type.equals("java/util/concurrent/Executors") && name.startsWith("new")) {
                return "Executors." + name;
            }
        }
        return null;
    }

    private static boolean isOwner(final String internalName) {
        for (final String owner : OWNERS) {
            if (internalName.equals(owner) || internalName.startsWith(owner + '$')) {
                return true;
            }
        }
        return false;
    }
}
