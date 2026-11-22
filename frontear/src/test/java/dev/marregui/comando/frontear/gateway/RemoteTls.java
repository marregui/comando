package dev.marregui.comando.frontear.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.assertj.core.api.Assertions.assertThat;

/// Machine keys for the remote listener's tests: an Ed25519 key pair and its self-signed certificate per machine,
/// made by the JDK's own `keytool` at test time, and the TLS 1.3 contexts built on them, each trusting exactly the
/// keys it pins, with no certificate authority and no host name, as ADR 0189 §2.1 has the machines do.
final class RemoteTls {

    /// The one protocol the remote listener speaks.
    static final String PROTOCOL = "TLSv1.3";
    /// The handshake bound ADR 0189 §2.1 states, for tests that do not test the bound itself.
    static final Duration HANDSHAKE = Duration.ofSeconds(10);
    private static final String ALIAS = "machine";
    private static final char[] PASSWORD = "frontear-test".toCharArray();
    // keytool makes one Ed25519 key in about half a second; a minute is room for a loaded machine
    private static final long KEYTOOL_SECONDS = 60;
    // a read waiting longer than this fails the test; the gateway answers a loopback client in milliseconds
    private static final int READ_MILLIS = 10_000;

    private RemoteTls() {
    }

    /// The key and certificate keytool made for machine `machine`, in `dir`.
    record Machine(String machine, KeyStore store, X509Certificate certificate) {

        /// The machine's public key, which a peer pins.
        PublicKey key() {
            return certificate.getPublicKey();
        }
    }

    /// Makes machine `machine`'s key pair and certificate, CN its id, in a PKCS12 store under `dir`.
    static Machine make(final Path dir, final String machine) throws IOException, GeneralSecurityException,
            InterruptedException {
        final Path store = dir.resolve(machine + ".p12");
        final Path output = dir.resolve(machine + ".keytool.txt");
        final Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        final Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-keyalg", "Ed25519", "-alias", ALIAS,
                "-dname", "CN=" + machine, "-validity", "7300", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-noprompt")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(p.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS)).as("keytool finished").isTrue();
            assertThat(p.exitValue()).as("keytool exit, its output in " + output).isZero();
        } finally {
            p.destroyForcibly();
        }
        final KeyStore keys = KeyStore.getInstance(store.toFile(), PASSWORD);
        return new Machine(machine, keys, (X509Certificate) keys.getCertificate(ALIAS));
    }

    /// A TLS 1.3 context that presents `self`'s certificate and accepts exactly the peers whose key is `pinned`'s.
    static SSLContext context(final Machine self, final Machine... pinned) throws GeneralSecurityException {
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(self.store(), PASSWORD);
        final SSLContext context = SSLContext.getInstance(PROTOCOL);
        context.init(keys.getKeyManagers(), new TrustManager[] {new Pinning(List.of(pinned))}, null);
        return context;
    }

    /// The listener's `machineOf`: each of `paired`'s id by its key, null for any other key.
    static Function<Certificate, String> machineOf(final Machine... paired) {
        final Map<PublicKey, String> byKey = new HashMap<>();
        for (final Machine m : paired) {
            byKey.put(m.key(), m.machine());
        }
        return peer -> byKey.get(peer.getPublicKey());
    }

    /// A remote listener on a free loopback port for machine `here`, its contexts from `ssl`.
    static RemoteListen listen(final SSLContext ssl, final Function<Certificate, String> machineOf, final String here,
            final int maxConnections, final Duration handshake) {
        return listen(ssl, machineOf, here, 0, maxConnections, handshake);
    }

    /// As above, on loopback port `port`.
    static RemoteListen listen(final SSLContext ssl, final Function<Certificate, String> machineOf, final String here,
            final int port, final int maxConnections, final Duration handshake) {
        return new RemoteListen(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), ssl, machineOf, here,
                maxConnections, handshake);
    }

    /// A TLS client from `context` on loopback `port`, TLS 1.3 alone, its handshake run by its first write.
    static RawClient dial(final SSLContext context, final int port) throws IOException {
        final SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(),
                port);
        socket.setEnabledProtocols(new String[] {PROTOCOL});
        socket.setSoTimeout(READ_MILLIS);
        return RawClient.over(socket);
    }

    /// Accepts a peer whose leaf certificate's key is one it pins, whatever signed it; no host name is checked.
    private static final class Pinning implements X509TrustManager {

        private final Set<PublicKey> pinned;

        Pinning(final List<Machine> machines) {
            pinned = Set.copyOf(machines.stream().map(Machine::key).toList());
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            check(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        private void check(final X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || !pinned.contains(chain[0].getPublicKey())) {
                throw new CertificateException("peer key not pinned");
            }
        }
    }
}
