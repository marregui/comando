package dev.marregui.comando.frontear.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import javax.security.auth.x500.X500Principal;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.port.MachinesPort;

/**
 * This machine's Ed25519 key and self-signed certificate, subject and issuer {@code CN=<machine>}, valid 20 years,
 * kept in a folder as {@value #KEY_FILE} (PKCS#8 DER) and {@value #CERTIFICATE_FILE} (DER), both readable and
 * writable by their owner alone. The key is what other machines pin: its {@link #fingerprint()} names it.
 */
public final class MachineKey {

    /** The private key's file in the folder. */
    static final String KEY_FILE = "machine.key";
    /** The certificate's file in the folder. */
    static final String CERTIFICATE_FILE = "machine.crt";
    private static final System.Logger LOG = System.getLogger(MachineKey.class.getName());
    private static final String ALGORITHM = "Ed25519";
    /**
     * A choice: longer than a laptop lives, so a certificate is never renewed and a pairing never lapses; an end past
     * 2049 {@link SelfSigned} writes as GeneralizedTime (RFC 5280 §4.1.2.5).
     */
    private static final long VALID_YEARS = 20;
    private static final byte[] PROBE = "comando machine key".getBytes(StandardCharsets.US_ASCII);

    private final String machine;
    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final String fingerprint;

    private MachineKey(final String machine, final PrivateKey privateKey, final X509Certificate certificate) {
        this.machine = machine;
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.fingerprint = fingerprint(certificate.getPublicKey());
    }

    /**
     * The key in {@code dir}, else a new one, written: the folder is made and narrowed to its owner, and both files
     * are narrowed at each load. Half a pair, as a crash between the two writes leaves it, is replaced, since no
     * machine pinned a key whose certificate was never written. A certificate for another machine id is issued again
     * for the same key, so the fingerprint other machines pinned stays.
     *
     * @throws IOException when a pair is there and does not read, or its certificate is not for its key: removing
     *                     both files makes a new one
     * @throws IllegalArgumentException when {@code machine} is not a machine id
     */
    public static MachineKey loadOrCreate(final Path dir, final String machine) throws IOException {
        Objects.requireNonNull(dir, "dir");
        if (!MachinesPort.MACHINE_ID.matcher(machine).matches()) {
            throw new IllegalArgumentException("not a machine id [machine=" + machine + ", pattern="
                    + MachinesPort.MACHINE_ID + ']');
        }
        OwnerOnly.folder(dir);
        final Path keyFile = dir.resolve(KEY_FILE);
        final Path certificateFile = dir.resolve(CERTIFICATE_FILE);
        if (Files.isRegularFile(keyFile) && Files.isRegularFile(certificateFile)) {
            OwnerOnly.narrow(keyFile);
            OwnerOnly.narrow(certificateFile);
            final PrivateKey key;
            final X509Certificate certificate;
            try {
                key = KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(Mapped.bytes(keyFile)));
                certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Mapped.bytes(certificateFile)));
                if (!pair(key, certificate.getPublicKey())) {
                    throw new GeneralSecurityException("certificate is not for this key");
                }
            } catch (final GeneralSecurityException | ClassCastException e) {
                throw new IOException("not an Ed25519 key and its certificate; remove both files to make a new one"
                        + " [folder=" + dir + ", key=" + KEY_FILE + ", certificate=" + CERTIFICATE_FILE + ']', e);
            }
            if (certificate.getSubjectX500Principal().equals(new X500Principal("CN=" + machine))) {
                return new MachineKey(machine, key, certificate);
            }
            final X509Certificate renamed = write(certificateFile, key, certificate.getPublicKey(), machine);
            LOG.log(System.Logger.Level.INFO, "machine certificate issued again [folder=" + dir + ", machine="
                    + machine + ", was=" + certificate.getSubjectX500Principal().getName() + ']');
            return new MachineKey(machine, key, renamed);
        }
        final KeyPair pair;
        try {
            pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no key pair generator [algorithm=" + ALGORITHM + ']', e);
        }
        // a temporary file is born readable by its owner alone, so the private key is never readable by another
        AtomicFiles.write(keyFile, pair.getPrivate().getEncoded());
        OwnerOnly.narrow(keyFile);
        final MachineKey made = new MachineKey(machine, pair.getPrivate(),
                write(certificateFile, pair.getPrivate(), pair.getPublic(), machine));
        LOG.log(System.Logger.Level.INFO, "machine key made [folder=" + dir + ", machine=" + machine
                + ", fingerprint=" + made.fingerprint + ']');
        return made;
    }

    /** The SHA-256 of {@code key}'s encoded form (its SubjectPublicKeyInfo), 64 lowercase hex digits. */
    public static String fingerprint(final PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256", e);
        }
    }

    public String machine() {
        return machine;
    }

    public String fingerprint() {
        return fingerprint;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    X509Certificate certificate() {
        return certificate;
    }

    private static X509Certificate write(final Path file, final PrivateKey key, final PublicKey pub,
            final String machine) throws IOException {
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final X509Certificate certificate;
        try {
            certificate = SelfSigned.certificate(key, pub, machine, now,
                    now.atZone(ZoneOffset.UTC).plusYears(VALID_YEARS).toInstant());
            AtomicFiles.write(file, certificate.getEncoded());
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("no certificate made [machine=" + machine + ']', e);
        }
        OwnerOnly.narrow(file);
        return certificate;
    }

    /** {@code key} signs what {@code pub} verifies: the two halves of one pair. */
    private static boolean pair(final PrivateKey key, final PublicKey pub) throws GeneralSecurityException {
        final Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(key);
        signer.update(PROBE);
        final Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(pub);
        verifier.update(PROBE);
        return verifier.verify(signer.sign());
    }
}
