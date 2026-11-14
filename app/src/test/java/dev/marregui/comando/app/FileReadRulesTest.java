package dev.marregui.comando.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.files.Mapped;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.properties.HasParameterTypes.Predicates.rawParameterTypes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// Record 140's rule, checked over every main class of comando: a file is read through `core.files.Mapped`, so a new
/// part cannot bring back a whole-file read without changing this test.
class FileReadRulesTest {

    /// No class calls the `Files` methods that read a file whole or as a stream.
    @Test
    void noClassReadsAFileWithFiles() {
        noClasses().should().callMethod(Files.class, "readString", Path.class)
                .orShould().callMethod(Files.class, "readString", Path.class, Charset.class)
                .orShould().callMethod(Files.class, "readAllBytes", Path.class)
                .orShould().callMethod(Files.class, "readAllLines", Path.class)
                .orShould().callMethod(Files.class, "readAllLines", Path.class, Charset.class)
                .orShould().callMethod(Files.class, "lines", Path.class)
                .orShould().callMethod(Files.class, "lines", Path.class, Charset.class)
                .orShould().callMethod(Files.class, "newBufferedReader", Path.class)
                .orShould().callMethod(Files.class, "newBufferedReader", Path.class, Charset.class)
                .orShould().callMethod(Files.class, "newInputStream", Path.class, OpenOption[].class)
                .check(ComandoClasses.MAIN);
    }

    // cdb's one class that opens files; package-private, so named rather than referenced
    private static final String CDB_STORE_FILES = "dev.marregui.comando.cdb.store.StoreFiles";

    /// No class hands a `File` to a JSON reader, or opens a channel on a file but the lock on the data root, the
    /// machine's QuestDB's start lock (record 146), and cdb's store, which owns its column files and depends on the JDK
    /// alone (record 158). comando's own `core.json.Json` takes no `File` (record 157).
    @Test
    void noClassReadsAFileByAnotherRoute() {
        noClasses().should().callMethodWhere(target(name("readTree")).and(target(rawParameterTypes(File.class))))
                .orShould().callMethodWhere(target(name("readValue")).and(target(rawParameterTypes(File.class,
                        Class.class))))
                .orShould().callMethod(Files.class, "newByteChannel", Path.class, OpenOption[].class)
                .check(ComandoClasses.MAIN);
        noClasses().that().doNotHaveFullyQualifiedName(Mapped.class.getName())
                .and().doNotHaveFullyQualifiedName(Main.class.getName())
                .and().doNotHaveFullyQualifiedName(MachineQuestDb.class.getName())
                .and().doNotHaveFullyQualifiedName(CDB_STORE_FILES)
                .should().callMethodWhere(target(name("open")).and(target(owner(assignableTo(FileChannel.class)))))
                .check(ComandoClasses.MAIN);
    }

    /// Bytes leave a mapping through `Mapped` only, byte by byte: a compiled bulk copy that meets a cut file ends the
    /// JVM.
    @Test
    void noClassCopiesBytesOffASegmentInBulk() {
        noClasses().should().callMethodWhere(target(owner(assignableTo(MemorySegment.class)))
                .and(target(name("toArray")).or(target(name("copy"))).or(target(name("asByteBuffer")))))
                .check(ComandoClasses.MAIN);
    }

    /// No class opens a file by the older stream and random-access types.
    @Test
    void noClassOpensAFileWithTheOlderTypes() {
        noClasses().should().dependOnClassesThat().areAssignableTo(FileInputStream.class)
                .orShould().dependOnClassesThat().areAssignableTo(FileReader.class)
                .orShould().dependOnClassesThat().areAssignableTo(RandomAccessFile.class)
                .check(ComandoClasses.MAIN);
    }
}
