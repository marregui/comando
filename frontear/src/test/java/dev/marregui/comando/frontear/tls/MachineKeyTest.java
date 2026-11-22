package dev.marregui.comando.frontear.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// This machine's key in a folder: made once and loaded after, owner-only, a self-signed certificate for the machine
/// id valid 20 years, issued again for the same key under another id, half a pair replaced, a pair that does not
/// belong together refused, and the fingerprint as the SHA-256 of the encoded public key.
class MachineKeyTest {

    @TempDir
    Path dir;

    /// The second load reads the key the first made: same fingerprint, same certificate; the folder and both files
    /// are readable and writable by their owner alone.
    @Test
    void aKeyIsMadeOnceAndLoadedAfterOwnerOnly() throws Exception {
        final Path folder = dir.resolve(".comando-machine");
        final MachineKey made = MachineKey.loadOrCreate(folder, "m1");
        final MachineKey loaded = MachineKey.loadOrCreate(folder, "m1");
        assertThat(loaded.fingerprint()).isEqualTo(made.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(loaded.machine()).isEqualTo("m1");
        assertThat(loaded.certificate()).isEqualTo(made.certificate());
        assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(folder.resolve(MachineKey.KEY_FILE)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(folder.resolve(MachineKey.CERTIFICATE_FILE)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /// A file another process widened is narrowed again at the next load.
    @Test
    void aWidenedFileIsNarrowedAtTheNextLoad() throws Exception {
        MachineKey.loadOrCreate(dir, "m1");
        Files.setPosixFilePermissions(dir.resolve(MachineKey.KEY_FILE), PosixFilePermissions.fromString("rw-r--r--"));
        MachineKey.loadOrCreate(dir, "m1");
        assertThat(Files.getPosixFilePermissions(dir.resolve(MachineKey.KEY_FILE)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /// The certificate is X.509 v3, self-signed with Ed25519, subject and issuer `CN=<machine>`, not a certificate
    /// authority (a critical basic constraints extension), valid 20 years from when it was made.
    @Test
    void theCertificateIsSelfSignedForTheMachineFor20Years() throws Exception {
        final X509Certificate c = MachineKey.loadOrCreate(dir, "edge-7").certificate();
        assertThat(c.getVersion()).isEqualTo(3);
        assertThat(c.getSubjectX500Principal()).isEqualTo(new X500Principal("CN=edge-7"));
        assertThat(c.getIssuerX500Principal()).isEqualTo(c.getSubjectX500Principal());
        assertThat(c.getSigAlgName()).isEqualTo("Ed25519");
        c.verify(c.getPublicKey());
        c.checkValidity();
        assertThat(c.getBasicConstraints()).isEqualTo(-1);
        assertThat(c.getCriticalExtensionOIDs()).containsExactly("2.5.29.19");
        assertThat(c.getNotAfter().toInstant())
                .isEqualTo(c.getNotBefore().toInstant().atZone(ZoneOffset.UTC).plusYears(20).toInstant());
    }

    /// A load under another machine id issues the certificate again for the same key: the fingerprint other machines
    /// pinned stays, and the new name is kept.
    @Test
    void anotherMachineIdIssuesTheCertificateAgainForTheSameKey() throws Exception {
        final MachineKey before = MachineKey.loadOrCreate(dir, "old");
        final MachineKey renamed = MachineKey.loadOrCreate(dir, "new");
        assertThat(renamed.fingerprint()).isEqualTo(before.fingerprint());
        assertThat(renamed.certificate().getSubjectX500Principal()).isEqualTo(new X500Principal("CN=new"));
        assertThat(MachineKey.loadOrCreate(dir, "new").certificate()).isEqualTo(renamed.certificate());
    }

    /// A certificate for another key beside this key is refused, and nothing is overwritten.
    @Test
    void aCertificateForAnotherKeyIsRefused() throws Exception {
        final Path other = dir.resolve("other");
        MachineKey.loadOrCreate(other, "m1");
        final Path mine = dir.resolve("mine");
        MachineKey.loadOrCreate(mine, "m1");
        Files.copy(other.resolve(MachineKey.CERTIFICATE_FILE), mine.resolve(MachineKey.CERTIFICATE_FILE),
                StandardCopyOption.REPLACE_EXISTING);
        final byte[] key = Files.readAllBytes(mine.resolve(MachineKey.KEY_FILE));
        assertThatThrownBy(() -> MachineKey.loadOrCreate(mine, "m1")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(mine.resolve(MachineKey.KEY_FILE))).isEqualTo(key);
    }

    /// A key file that is not a key is refused rather than replaced.
    @Test
    void aKeyFileThatDoesNotReadIsRefused() throws Exception {
        MachineKey.loadOrCreate(dir, "m1");
        Files.write(dir.resolve(MachineKey.KEY_FILE), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> MachineKey.loadOrCreate(dir, "m1")).isInstanceOf(IOException.class);
    }

    /// Half a pair, as a crash between the two writes leaves it, is replaced by a new key.
    @Test
    void halfAPairIsReplaced() throws Exception {
        final MachineKey first = MachineKey.loadOrCreate(dir, "m1");
        Files.delete(dir.resolve(MachineKey.CERTIFICATE_FILE));
        final MachineKey second = MachineKey.loadOrCreate(dir, "m1");
        assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(MachineKey.loadOrCreate(dir, "m1").fingerprint()).isEqualTo(second.fingerprint());
    }

    /// A machine id outside `[a-z0-9-]{1,32}` is refused before anything is written.
    @Test
    void aBadMachineIdIsRefused() {
        for (final String bad : new String[] {"", "M1", "a b", "a/b", "x".repeat(33)}) {
            assertThatThrownBy(() -> MachineKey.loadOrCreate(dir.resolve("k"), bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(dir.resolve("k")).doesNotExist();
    }

    /// The fingerprint is the SHA-256 of the public key's encoded form, in lowercase hex.
    @Test
    void theFingerprintIsTheSha256OfTheEncodedPublicKey() throws Exception {
        final MachineKey key = MachineKey.loadOrCreate(dir, "m1");
        final byte[] encoded = key.certificate().getPublicKey().getEncoded();
        assertThat(key.fingerprint())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)))
                .isEqualTo(MachineKey.fingerprint(key.certificate().getPublicKey()));
    }
}
