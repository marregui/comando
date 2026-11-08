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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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

    private static JavaClasses comando;

    @BeforeAll
    static void load() {
        comando = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.marregui.comando");
    }

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
                .check(comando);
    }

    /// No class hands a `File` to the JSON reader, or opens a channel on a file but the lock on the data root.
    @Test
    void noClassReadsAFileByAnotherRoute() {
        noClasses().should().callMethod(ObjectMapper.class, "readTree", File.class)
                .orShould().callMethodWhere(target(name("readValue")).and(target(rawParameterTypes(File.class,
                        Class.class))))
                .orShould().callMethod(Files.class, "newByteChannel", Path.class, OpenOption[].class)
                .check(comando);
        noClasses().that().doNotHaveFullyQualifiedName(Mapped.class.getName())
                .and().doNotHaveFullyQualifiedName(Main.class.getName())
                .should().callMethodWhere(target(name("open")).and(target(owner(assignableTo(FileChannel.class)))))
                .check(comando);
    }

    /// Bytes leave a mapping through `Mapped` only, byte by byte: a compiled bulk copy that meets a cut file ends the
    /// JVM.
    @Test
    void noClassCopiesBytesOffASegmentInBulk() {
        noClasses().should().callMethodWhere(target(owner(assignableTo(MemorySegment.class)))
                .and(target(name("toArray")).or(target(name("copy"))).or(target(name("asByteBuffer")))))
                .check(comando);
    }

    /// No class opens a file by the older stream and random-access types.
    @Test
    void noClassOpensAFileWithTheOlderTypes() {
        noClasses().should().dependOnClassesThat().areAssignableTo(FileInputStream.class)
                .orShould().dependOnClassesThat().areAssignableTo(FileReader.class)
                .orShould().dependOnClassesThat().areAssignableTo(RandomAccessFile.class)
                .check(comando);
    }
}
