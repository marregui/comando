package dev.marregui.comando.frontear.tls;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Trusts a peer, client or server alike, exactly when the public key of its chain's first certificate is pinned and
 * the session is TLS 1.3: no certificate authority, no validity dates, no host name. Its issuers are none, so a server
 * asks a client for any certificate.
 */
final class PinningTrustManager extends X509ExtendedTrustManager {

    private static final X509Certificate[] NO_ISSUERS = new X509Certificate[0];

    private final Pins pins;

    PinningTrustManager(final Pins pins) {
        this.pins = pins;
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        check(chain, socket instanceof final SSLSocket s ? s.getHandshakeSession() : null);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        check(chain, socket instanceof final SSLSocket s ? s.getHandshakeSession() : null);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
        check(chain, engine == null ? null : engine.getHandshakeSession());
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
        check(chain, engine == null ? null : engine.getHandshakeSession());
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
        check(chain, null);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
        check(chain, null);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return NO_ISSUERS;
    }

    /** {@code session} is the handshake's, {@code null} when the caller has none to give. */
    private void check(final X509Certificate[] chain, final SSLSession session) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("no peer certificate");
        }
        if (session != null && !Tls.PROTOCOL.equals(session.getProtocol())) {
            throw new CertificateException("not a TLS 1.3 session [protocol=" + session.getProtocol() + ']');
        }
        if (pins.machineOf(chain[0]) == null) {
            throw new CertificateException("peer key not pinned [fingerprint="
                    + MachineKey.fingerprint(chain[0].getPublicKey()) + ", subject="
                    + chain[0].getSubjectX500Principal().getName() + ']');
        }
    }
}
