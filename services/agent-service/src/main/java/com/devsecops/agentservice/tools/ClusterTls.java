package com.devsecops.agentservice.tools;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The TLS trust a Pod needs to talk to its own cluster's API server.
 *
 * <p>A ServiceAccount's credentials are issued by the cluster's CA, which the
 * JVM's default trust store has never heard of, so every call to
 * {@code kubernetes.default.svc} fails certificate verification unless the CA
 * mounted into the Pod is trusted explicitly. This builds that one-certificate
 * trust store.
 *
 * <p>Shared by the read-only tools and the action executor rather than copied
 * into each: the two classes are the only places in the agent that speak to the
 * API server, they must agree about how to trust it, and two copies of a TLS
 * configuration is two places for it to be weakened.
 *
 * <p>Hostname verification is left on. The CA has to cover
 * {@code kubernetes.default.svc}, which is the name the agent uses, so there is
 * nothing here that turns verification off to make a connection work.
 */
public final class ClusterTls {

    private ClusterTls() {
        // A static helper, not a type.
    }

    /**
     * A context that trusts the certificate in {@code caFile} and everything the
     * JVM already trusts.
     *
     * @throws Exception when the file is not a certificate or the platform
     *                   refuses the trust store; the caller decides whether that
     *                   is fatal, and for the read-only tools it is not
     */
    public static SSLContext trusting(Path caFile) throws Exception {
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(caFile)) {
            X509Certificate ca = (X509Certificate) certificates.generateCertificate(in);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("kubernetes-ca", ca);

            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        }
    }
}
