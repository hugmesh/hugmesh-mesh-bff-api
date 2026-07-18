package systems.snapdragon.meshbff.devices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reactive ROLE_SERVICE client over user-bff's INTERNAL mesh-devices surface:
 *
 * <pre>GET {user-bff}/bff/user/internal/mesh-devices
 *   -&gt; 200 [{nodeId, localpart, displayName, visibility}, ...]</pre>
 *
 * <p>Cloned from snapdragon-admin-bff-api's
 * {@code notification.UserBffNotificationClient} — the platform's established
 * shape for a ROLE_SERVICE caller of user-bff's internal surface: a Casdoor
 * {@code client_credentials}-minted SERVICE JWT (the token {@link Supplier}
 * from {@link UserBffMeshDeviceConfig#serviceTokenSupplier}, cached +
 * re-minted), forwarded {@code Authorization: Bearer <token>} on every call,
 * PLUS (unlike that mod-bff/admin-bff precedent) the outbound client-cert mTLS
 * connector ({@link UserBffHttpClientSsl}) wired onto the {@link WebClient} at
 * construction — mirroring the mTLS+client-credentials combination the
 * meshbridge daemon's own {@code UserBffClient} presents to this exact
 * endpoint (see {@code matrix_cs.py}'s {@code UserBffClient}/{@code
 * CasdoorTokenSource}).
 *
 * <h3>GATED, fail-soft (last-good, never a hard failure)</h3>
 * The WebClient is always built + injected non-null, but {@link #enabled()}
 * gates a real call: with the gate OFF (the default for a user-bff-less
 * dev/test/CI run) {@link #fetchAll()} returns an EMPTY list without a network
 * call. On a real call, a transport error / non-2xx / timeout also degrades to
 * an error the caller ({@code RegistrationCache}) turns into "keep the last-good
 * snapshot" — never a 5xx bubbled to the mesh BFF's own callers.
 */
public class UserBffMeshDeviceClient {

    private static final Logger log = LoggerFactory.getLogger(UserBffMeshDeviceClient.class);

    /** Bound the call so a slow/unreachable user-bff never stalls a reload tick. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient http;
    private final boolean enabled;
    private final Supplier<Mono<String>> serviceToken;

    /**
     * @param http         WebClient pre-pointed at user-bff's
     *                     {@code /bff/user/internal/mesh-devices} base URL, mTLS-wired.
     * @param enabled      whether to make real calls (false short-circuits to an empty list).
     * @param serviceToken supplies a current Casdoor SERVICE bearer (cached / re-minted).
     */
    public UserBffMeshDeviceClient(WebClient http, boolean enabled, Supplier<Mono<String>> serviceToken) {
        this.http = http;
        this.enabled = enabled;
        this.serviceToken = serviceToken;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * The FULL registration mapping (every active claim, across every owner and
     * domain — user-bff's endpoint is platform-level, not caller-scoped). Callers
     * filter/gate it themselves (see {@code devices.RegistrationCache} +
     * {@code directory.Visibility}). Never throws: any failure propagates as a
     * {@link Mono} error for the caller's own fail-soft handling.
     */
    public Mono<List<Registration>> fetchAll() {
        if (!enabled) {
            log.debug("[mesh-devices] user-bff leg gate OFF; returning an empty mapping (no call)");
            return Mono.just(List.of());
        }
        return serviceToken.get()
                .flatMap(token -> http.get()
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToFlux(Registration.class)
                        .collectList())
                .timeout(CALL_TIMEOUT)
                .doOnError(e -> log.warn("[mesh-devices] user-bff fetch failed: {}", e.toString()));
    }
}
