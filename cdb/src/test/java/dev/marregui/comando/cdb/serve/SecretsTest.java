package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The auth folder's two secrets: made owner-only when absent, read back the same when present, refused when a file
/// holds anything but one secret, and told apart by the role each one gives.
class SecretsTest {

    private static final String OWNER_ONLY = "rw-------";

    @TempDir
    private Path root;

    /// A folder that does not exist yet gets itself and both files, owner-only, each one line of 64 lowercase hex
    /// characters, two different secrets, and nothing else; a second load reads the same secrets.
    @Test
    void loadMakesBothSecretsOwnerOnlyAndReadsThemBack() throws IOException {
        final Path folder = root.resolve("auth");

        final Secrets made = Secrets.load(folder);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwx------");
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(file -> file.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(Secrets.OWNER_FILE, Secrets.READ_ONLY_FILE);
        }
        final String owner = content(folder.resolve(Secrets.OWNER_FILE));
        final String readOnly = content(folder.resolve(Secrets.READ_ONLY_FILE));
        for (final String line : List.of(owner, readOnly)) {
            assertThat(line).matches("[0-9a-f]{64}\n");
        }
        assertThat(owner).isNotEqualTo(readOnly);
        for (final String name : List.of(Secrets.OWNER_FILE, Secrets.READ_ONLY_FILE)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve(name))))
                    .isEqualTo(OWNER_ONLY);
        }
        final Secrets reloaded = Secrets.load(folder);
        for (final Secrets secrets : List.of(made, reloaded)) {
            assertThat(secrets.roleOf(owner.strip())).isEqualTo(Role.OWNER);
            assertThat(secrets.roleOf(readOnly.strip())).isEqualTo(Role.READ_ONLY);
        }
        assertThat(content(folder.resolve(Secrets.OWNER_FILE))).isEqualTo(owner);
    }

    /// No secret, an empty one, a wrong one, a secret with its newline and one in upper case give no role.
    @Test
    void anythingButASecretGivesNoRole() throws IOException {
        final String owner = "abcdef0123456789".repeat(4);
        Files.writeString(root.resolve(Secrets.OWNER_FILE), owner + '\n', StandardCharsets.US_ASCII);
        final Secrets secrets = Secrets.load(root);

        assertThat(secrets.roleOf(null)).isEqualTo(Role.NONE);
        assertThat(secrets.roleOf("")).isEqualTo(Role.NONE);
        assertThat(secrets.roleOf("0".repeat(64))).isEqualTo(Role.NONE);
        assertThat(secrets.roleOf(owner + "\n")).isEqualTo(Role.NONE);
        assertThat(secrets.roleOf(owner.substring(1))).isEqualTo(Role.NONE);
        assertThat(secrets.roleOf(owner.toUpperCase(Locale.ROOT))).isEqualTo(Role.NONE);
        assertThat(secrets.roleOf(owner)).isEqualTo(Role.OWNER);
    }

    /// A secret file holding anything but 64 lowercase hex characters and a newline is refused, naming the file, the
    /// operator's cue to fix or delete it, and is left as it was.
    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "abc\n", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef\n",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg\n",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n\n",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\r\n"})
    void refusesAFileThatIsNotOneSecret(final String bad) throws IOException {
        final Path file = root.resolve(Secrets.OWNER_FILE);
        Files.writeString(file, bad, StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> Secrets.load(root)).isInstanceOf(IOException.class)
                .hasMessageContaining("[file=" + file + ']');
        assertThat(content(file)).isEqualTo(bad);
    }

    /// Two files holding one secret would give the read-only client the owner's role: refused, naming the second.
    @Test
    void refusesTheSameSecretInBothFiles() throws IOException {
        final String line = "0123456789abcdef".repeat(4) + '\n';
        Files.writeString(root.resolve(Secrets.OWNER_FILE), line, StandardCharsets.US_ASCII);
        Files.writeString(root.resolve(Secrets.READ_ONLY_FILE), line, StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> Secrets.load(root)).isInstanceOf(IOException.class)
                .hasMessageContaining("[file=" + root.resolve(Secrets.READ_ONLY_FILE) + ']');
    }

    /// A present secret that others may read keeps its value and is narrowed back to owner-only.
    @Test
    void narrowsASecretOthersCanRead() throws IOException {
        final String line = "89abcdef01234567".repeat(4) + '\n';
        final Path file = root.resolve(Secrets.OWNER_FILE);
        Files.writeString(file, line, StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        final Secrets secrets = Secrets.load(root);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo(OWNER_ONLY);
        assertThat(secrets.roleOf(line.strip())).isEqualTo(Role.OWNER);
        assertThat(content(file)).isEqualTo(line);
    }

    /// Servers that start at once on one empty folder agree on one secret of each kind, and leave no temporary file.
    @Test
    void loadsAtOnceAgreeOnOneSecret() throws Exception {
        final int loaders = 8;
        final Path folder = root.resolve("auth");
        final CyclicBarrier start = new CyclicBarrier(loaders);
        final CountDownLatch done = new CountDownLatch(loaders);
        final List<Secrets> loaded = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < loaders; i++) {
            Thread.ofVirtual().name("secrets-test-" + i).start(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    loaded.add(Secrets.load(folder));
                } catch (final Exception e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        final String owner = content(folder.resolve(Secrets.OWNER_FILE)).strip();
        final String readOnly = content(folder.resolve(Secrets.READ_ONLY_FILE)).strip();
        assertThat(loaded).hasSize(loaders).allSatisfy(secrets -> {
            assertThat(secrets.roleOf(owner)).isEqualTo(Role.OWNER);
            assertThat(secrets.roleOf(readOnly)).isEqualTo(Role.READ_ONLY);
        });
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.count()).isEqualTo(2L);
        }
    }

    private static String content(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.US_ASCII);
    }
}
