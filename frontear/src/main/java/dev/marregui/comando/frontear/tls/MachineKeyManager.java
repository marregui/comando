package dev.marregui.comando.frontear.tls;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Offers this machine's key and certificate, under one alias, whenever the handshake asks for a key of its algorithm
 * (the JDK names an Ed25519 key's type {@code EdDSA}), as a client and as a server; the issuers a peer names are not
 * looked at, since a self-signed certificate has no issuer but itself.
 */
final class MachineKeyManager extends X509ExtendedKeyManager {

    private static final String ALIAS = "machine";

    private final MachineKey key;
    private final String[] aliases = {ALIAS};

    MachineKeyManager(final MachineKey key) {
        this.key = key;
    }

    @Override
    public String[] getClientAliases(final String keyType, final Principal[] issuers) {
        return fits(keyType) ? aliases.clone() : null;
    }

    @Override
    public String chooseClientAlias(final String[] keyTypes, final Principal[] issuers, final Socket socket) {
        return choose(keyTypes);
    }

    @Override
    public String chooseEngineClientAlias(final String[] keyTypes, final Principal[] issuers, final SSLEngine engine) {
        return choose(keyTypes);
    }

    @Override
    public String[] getServerAliases(final String keyType, final Principal[] issuers) {
        return fits(keyType) ? aliases.clone() : null;
    }

    @Override
    public String chooseServerAlias(final String keyType, final Principal[] issuers, final Socket socket) {
        return fits(keyType) ? ALIAS : null;
    }

    @Override
    public String chooseEngineServerAlias(final String keyType, final Principal[] issuers, final SSLEngine engine) {
        return fits(keyType) ? ALIAS : null;
    }

    @Override
    public X509Certificate[] getCertificateChain(final String alias) {
        return ALIAS.equals(alias) ? new X509Certificate[] {key.certificate()} : null;
    }

    @Override
    public PrivateKey getPrivateKey(final String alias) {
        return ALIAS.equals(alias) ? key.privateKey() : null;
    }

    private String choose(final String[] keyTypes) {
        if (keyTypes != null) {
            for (final String t : keyTypes) {
                if (fits(t)) {
                    return ALIAS;
                }
            }
        }
        return null;
    }

    private boolean fits(final String keyType) {
        return key.privateKey().getAlgorithm().equals(keyType);
    }
}
