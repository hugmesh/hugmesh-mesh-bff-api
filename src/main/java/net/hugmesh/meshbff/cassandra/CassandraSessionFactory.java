package net.hugmesh.meshbff.cassandra;

import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Builds a hardened {@link CqlSessionBuilder} for the mesh directory's
 * best-effort Cassandra mirror — cloned from hugmesh-admin-bff-api's
 * {@code cassandra.CassandraSessionFactory} verbatim (same property namespace,
 * same gated auth/TLS shape) so a single Vault/ESO Cassandra credential set
 * configures every writer on the platform identically.
 */
@Component
public class CassandraSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(CassandraSessionFactory.class);

    private final boolean authEnabled;
    private final String authUsername;
    private final String authPassword;
    private final boolean sslEnabled;
    private final String trustStorePath;
    private final String trustStoreType;
    private final String trustStorePassword;
    private final String keyStorePath;
    private final String keyStoreType;
    private final String keyStorePassword;

    public CassandraSessionFactory(
            @Value("${app.mirror.cassandra.auth.enabled:false}") boolean authEnabled,
            @Value("${app.mirror.cassandra.auth.username:}") String authUsername,
            @Value("${app.mirror.cassandra.auth.password:}") String authPassword,
            @Value("${app.mirror.cassandra.ssl.enabled:false}") boolean sslEnabled,
            @Value("${app.mirror.cassandra.ssl.trust-store:/stores/cassandra-truststore.p12}") String trustStorePath,
            @Value("${app.mirror.cassandra.ssl.trust-store-type:PKCS12}") String trustStoreType,
            @Value("${app.mirror.cassandra.ssl.trust-store-password:}") String trustStorePassword,
            @Value("${app.mirror.cassandra.ssl.key-store:}") String keyStorePath,
            @Value("${app.mirror.cassandra.ssl.key-store-type:PKCS12}") String keyStoreType,
            @Value("${app.mirror.cassandra.ssl.key-store-password:}") String keyStorePassword) {
        this.authEnabled = authEnabled;
        this.authUsername = authUsername;
        this.authPassword = authPassword;
        this.sslEnabled = sslEnabled;
        this.trustStorePath = trustStorePath;
        this.trustStoreType = trustStoreType;
        this.trustStorePassword = trustStorePassword;
        this.keyStorePath = keyStorePath;
        this.keyStoreType = keyStoreType;
        this.keyStorePassword = keyStorePassword;
    }

    public boolean authEnabled() {
        return authEnabled;
    }

    public boolean sslEnabled() {
        return sslEnabled;
    }

    public CqlSessionBuilder harden(CqlSessionBuilder builder) {
        if (authEnabled) {
            if (authUsername == null || authUsername.isBlank()) {
                throw new IllegalStateException(
                        "app.mirror.cassandra.auth.enabled=true but app.mirror.cassandra.auth.username is blank "
                        + "(set CASSANDRA_USERNAME via the ESO Secret — never hardcode)");
            }
            builder = builder.withAuthCredentials(authUsername, authPassword == null ? "" : authPassword);
            log.info("[cassandra] native auth ENABLED (username from env; password never logged)");
        }
        if (sslEnabled) {
            builder = builder.withSslContext(buildSslContext());
            log.info("[cassandra] TLS ENABLED (truststore={}, client-cert={})",
                    trustStorePath, (keyStorePath != null && !keyStorePath.isBlank()) ? "present" : "none");
        }
        return builder;
    }

    private SSLContext buildSslContext() {
        if (trustStorePath == null || trustStorePath.isBlank()) {
            throw new IllegalStateException(
                    "app.mirror.cassandra.ssl.enabled=true but app.mirror.cassandra.ssl.trust-store is blank");
        }
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(loadStore(trustStorePath, trustStoreType, trustStorePassword));

            KeyManagerFactory kmf = null;
            if (keyStorePath != null && !keyStorePath.isBlank()) {
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                KeyStore ks = loadStore(keyStorePath, keyStoreType, keyStorePassword);
                kmf.init(ks, (keyStorePassword == null ? "" : keyStorePassword).toCharArray());
            }

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("[cassandra] failed to build TLS SSLContext: " + e, e);
        }
    }

    private static KeyStore loadStore(String path, String type, String password) throws Exception {
        Path p = Path.of(path);
        if (!Files.isReadable(p)) {
            throw new IllegalStateException("[cassandra] store not readable: " + path
                    + " (mount the PKCS12 store into the pod; never commit it to git)");
        }
        KeyStore store = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
        try (InputStream in = Files.newInputStream(p)) {
            store.load(in, (password == null ? "" : password).toCharArray());
        }
        return store;
    }
}
