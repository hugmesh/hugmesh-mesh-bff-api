package net.hugmesh.meshbff.devices;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Wires {@link UserBffMeshDeviceClient} — a ROLE_SERVICE WebClient over
 * user-bff's internal mesh-devices surface, presenting BOTH this BFF's mTLS
 * client identity ({@link UserBffHttpClientSsl}, mirroring
 * {@code AdminBffInternalConfig}'s reuse pattern) AND a Casdoor
 * {@code client_credentials}-minted SERVICE bearer (the caching token supplier
 * shape cloned from hugmesh-admin-bff-api's
 * {@code notification.UserBffNotificationConfig.serviceTokenSupplier} —
 * identical to the mechanism hugmesh-actions' {@code
 * FederationUsernameClientConfig} and the meshbridge daemon's own {@code
 * CasdoorTokenSource} use for the SAME endpoint).
 *
 * <p><b>GATED STUB-SEAM.</b> The client bean is ALWAYS present (so
 * {@code RegistrationCache} injects it non-null) but real calls are gated by
 * {@code app.mesh.user-bff.enabled} (default {@code false}) so a
 * user-bff-less dev/test/CI run boots clean — with the gate off the client
 * returns an empty mapping without a call. The in-cluster deployment flips
 * {@code APP_MESH_USER_BFF_ENABLED=true} once the mTLS + Casdoor SERVICE
 * client credentials are provisioned — config-only, no code change.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserBffHttpClientSsl.class)
public class UserBffMeshDeviceConfig {

    /** Re-mint this long before stated expiry so a call never races the clock. */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);
    /** Fallback lifetime if Casdoor omits expires_in. */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    @Bean
    public UserBffMeshDeviceClient userBffMeshDeviceClient(
            UserBffHttpClientSsl mtls,
            @Value("${app.mesh.user-bff.enabled:false}") boolean enabled,
            @Value("${app.mesh.user-bff.base-url:"
                    + "https://snapdragon-user-bff-api.snapdragon-backend.svc.cluster.local:8080"
                    + "/bff/user/internal/mesh-devices}") String baseUrl,
            @Value("${app.casdoor.systems.base-url:}") String casdoorBaseUrl,
            @Value("${app.casdoor.systems.client-id:}") String clientId,
            @Value("${app.casdoor.systems.client-secret:}") String clientSecret) {

        HttpClient httpClient = mtls.customize(HttpClient.create());
        WebClient userBff = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        WebClient casdoor = WebClient.builder().baseUrl(casdoorBaseUrl).build();
        Supplier<Mono<String>> tokenSupplier = serviceTokenSupplier(casdoor, clientId, clientSecret);
        return new UserBffMeshDeviceClient(userBff, enabled, tokenSupplier);
    }

    /**
     * A caching client-credentials token supplier (identical shape to
     * hugmesh-admin-bff-api's {@code UserBffNotificationConfig
     * .serviceTokenSupplier} / hugmesh-actions'
     * {@code FederationUsernameClientConfig.serviceTokenSupplier}). Holds the
     * last minted token + the instant it should be considered stale, and
     * re-mints only when stale.
     */
    static Supplier<Mono<String>> serviceTokenSupplier(WebClient casdoor, String clientId, String clientSecret) {
        final AtomicReference<Cached> cache = new AtomicReference<>(null);
        return () -> Mono.defer(() -> {
            Cached c = cache.get();
            if (c != null && Instant.now().isBefore(c.staleAt())) {
                return Mono.just(c.token());
            }
            return mint(casdoor, clientId, clientSecret, cache);
        });
    }

    private static Mono<String> mint(WebClient casdoor, String clientId, String clientSecret,
                                     AtomicReference<Cached> cache) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return casdoor.post()
                .uri("/api/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .flatMap(json -> extractAndCacheToken(json, cache));
    }

    private static Mono<String> extractAndCacheToken(Map<String, Object> json, AtomicReference<Cached> cache) {
        Object at = json.get("access_token");
        if (!(at instanceof String s) || s.isBlank()) {
            return Mono.error(new IllegalStateException("casdoor token mint returned no access_token"));
        }
        Object exp = json.get("expires_in");
        long expiresIn = exp instanceof Number num ? num.longValue() : 0L;
        Duration ttl = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : DEFAULT_TTL;
        cache.set(new Cached(s, Instant.now().plus(ttl).minus(EXPIRY_SKEW)));
        return Mono.just(s);
    }

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_TYPE =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    private record Cached(String token, Instant staleAt) {
    }
}
