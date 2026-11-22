package dev.marregui.comando.frontear.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes a self-signed X.509 v3 certificate in DER, signed with Ed25519: the JDK reads certificates but has no public
 * way to make one. Subject and issuer are one common name; the one extension, critical, says it is no authority.
 */
final class SelfSigned {

    private static final String SIGNATURE = "Ed25519";
    private static final int SEQUENCE = 0x30;
    private static final int SET = 0x31;
    private static final int INTEGER = 0x02;
    private static final int BIT_STRING = 0x03;
    private static final int OCTET_STRING = 0x04;
    private static final int UTF8_STRING = 0x0C;
    private static final int UTC_TIME = 0x17;
    private static final int GENERALIZED_TIME = 0x18;
    private static final int EXPLICIT_0 = 0xA0;
    private static final int EXPLICIT_3 = 0xA3;
    /** The short form of a DER length holds up to 127. */
    private static final int SHORT_LENGTH_MAX = 0x7F;
    /** RFC 5280 §4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050. */
    private static final int UTC_TIME_LAST_YEAR = 2049;
    /** RFC 5280 §4.1.2.2: a serial number of at most 20 octets; 16 random ones, positive. */
    private static final int SERIAL_BYTES = 16;
    /** Version v3 is encoded as 2. */
    private static final byte[] V3 = {INTEGER, 0x01, 0x02};
    /** The OID 1.3.101.112, id-Ed25519 (RFC 8410), with its tag and length. */
    private static final byte[] ED25519 = {0x06, 0x03, 0x2B, 0x65, 0x70};
    /** The OID 2.5.4.3, id-at-commonName. */
    private static final byte[] COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};
    /** The OID 2.5.29.19, id-ce-basicConstraints. */
    private static final byte[] BASIC_CONSTRAINTS = {0x06, 0x03, 0x55, 0x1D, 0x13};
    /** BOOLEAN TRUE, the extension's critical flag. */
    private static final byte[] TRUE = {0x01, 0x01, (byte) 0xFF};
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'");
    private static final DateTimeFormatter GENERALIZED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'");
    private static final SecureRandom RANDOM = new SecureRandom();

    private SelfSigned() {
    }

    /**
     * A certificate for {@code publicKey}, subject and issuer {@code CN=<commonName>}, valid from {@code notBefore}
     * to {@code notAfter} (to the second), signed with {@code privateKey}, its Ed25519 pair; read back by the JDK and
     * its signature checked before it is returned.
     *
     * @throws GeneralSecurityException when the key is not Ed25519 or the pair does not match
     */
    static X509Certificate certificate(final PrivateKey privateKey, final PublicKey publicKey, final String commonName,
            final Instant notBefore, final Instant notAfter) throws GeneralSecurityException {
        final byte[] algorithm = tlv(SEQUENCE, ED25519);
        final byte[] name = tlv(SEQUENCE, tlv(SET, tlv(SEQUENCE, COMMON_NAME,
                tlv(UTF8_STRING, commonName.getBytes(StandardCharsets.UTF_8)))));
        final byte[] tbs = tlv(SEQUENCE,
                tlv(EXPLICIT_0, V3),
                tlv(INTEGER, serial()),
                algorithm,
                name,
                tlv(SEQUENCE, time(notBefore), time(notAfter)),
                name,
                publicKey.getEncoded(),
                tlv(EXPLICIT_3, tlv(SEQUENCE, tlv(SEQUENCE, BASIC_CONSTRAINTS, TRUE,
                        tlv(OCTET_STRING, tlv(SEQUENCE))))));
        final Signature signer = Signature.getInstance(SIGNATURE);
        signer.initSign(privateKey);
        signer.update(tbs);
        final byte[] der = tlv(SEQUENCE, tbs, algorithm, bitString(signer.sign()));
        final X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        certificate.verify(publicKey);
        return certificate;
    }

    private static byte[] serial() {
        final byte[] bytes = new byte[SERIAL_BYTES];
        RANDOM.nextBytes(bytes);
        return new BigInteger(1, bytes).max(BigInteger.ONE).toByteArray();
    }

    private static byte[] time(final Instant at) {
        final ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        if (utc.getYear() <= UTC_TIME_LAST_YEAR) {
            return tlv(UTC_TIME, UTC.format(utc).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(GENERALIZED_TIME, GENERALIZED.format(utc).getBytes(StandardCharsets.US_ASCII));
    }

    /** A BIT STRING of whole bytes: no unused bits. */
    private static byte[] bitString(final byte[] bytes) {
        final byte[] content = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return tlv(BIT_STRING, content);
    }

    /** One DER value: {@code tag}, the definite length of the parts, then the parts in order. */
    private static byte[] tlv(final int tag, final byte[]... parts) {
        int length = 0;
        for (final byte[] p : parts) {
            length += p.length;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(length + Integer.BYTES + 2);
        out.write(tag);
        if (length <= SHORT_LENGTH_MAX) {
            out.write(length);
        } else {
            final int octets = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + Byte.SIZE - 1) / Byte.SIZE;
            out.write(0x80 | octets);
            for (int i = octets - 1; i >= 0; i--) {
                out.write(length >>> (i * Byte.SIZE));
            }
        }
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
