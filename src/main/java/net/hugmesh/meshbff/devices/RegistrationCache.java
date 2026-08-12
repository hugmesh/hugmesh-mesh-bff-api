package net.hugmesh.meshbff.devices;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A {@code volatile}-snapshot cache of user-bff's full mesh-device registration
 * mapping ({@code nodeId -> Registration}), refreshed every 60s (default) by
 * {@link UserBffMeshDeviceClient#fetchAll()} — the SAME volatile-snapshot +
 * {@code @PostConstruct} seed + {@code @Scheduled} reload shape as
 * {@code directory.MeshDirectoryCache} / hugmesh-gateway's
 * {@code GatewayRouteAccessService}, applied here to an UPSTREAM service call
 * instead of a datastore read.
 *
 * <p><b>FAIL-SOFT, last-good.</b> Unlike the Postgres-backed directory cache
 * (which has a Cassandra fallback), a failed user-bff call has no fallback
 * datastore — the ONLY sane degrade is to keep serving the last successfully
 * fetched snapshot (never blank it on a transient failure, and never fail the
 * caller's request). An empty INITIAL snapshot (never yet refreshed
 * successfully) is a legitimate "no registrations known yet" state, not an
 * error — every node in {@code directory.MeshDirectoryCache} is then treated
 * as anonymous until the first successful reload.
 */
@Service
public class RegistrationCache {

    private static final Logger log = LoggerFactory.getLogger(RegistrationCache.class);

    private final UserBffMeshDeviceClient client;
    @Nullable
    private final ReactiveCircuitBreaker refreshBreaker;

    private volatile Map<String, Registration> byNodeId = Map.of();
    private volatile Instant lastRefreshAt = Instant.EPOCH;
    private volatile boolean everSucceeded = false;

    public RegistrationCache(UserBffMeshDeviceClient client,
                             @Nullable ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.client = client;
        this.refreshBreaker = circuitBreakerFactory != null
                ? circuitBreakerFactory.create("meshRegistrations")
                : null;
    }

    @PostConstruct
    void init() {
        refreshNow().subscribe();
    }

    @Scheduled(fixedDelayString = "${app.mesh.devices-cache.refresh-interval-ms:60000}")
    void refresh() {
        refreshNow().subscribe();
    }

    /** Package-private (not {@code private}) so {@code RegistrationCacheTest} can drive
     *  one reload deterministically without a {@code @Scheduled} tick. */
    Mono<Void> refreshNow() {
        Mono<List<Registration>> read = client.fetchAll();
        Mono<List<Registration>> guardedRead = refreshBreaker == null
                ? read
                : refreshBreaker.run(read, t -> {
                    log.warn("[mesh-registrations] meshRegistrations breaker fast-failed a refresh: {}",
                            t.toString());
                    return Mono.empty();
                });
        return guardedRead
                .doOnNext(this::swap)
                .onErrorResume(e -> {
                    log.warn("[mesh-registrations] refresh failed ({}) — keeping last-good snapshot "
                            + "({} row(s), last success {})",
                            e.toString(), byNodeId.size(), lastRefreshAt);
                    return Mono.empty();
                })
                .then();
    }

    private void swap(List<Registration> rows) {
        this.byNodeId = rows.stream()
                .filter(r -> r.nodeId() != null && !r.nodeId().isBlank())
                .collect(Collectors.toUnmodifiableMap(r -> r.nodeId().toLowerCase(),
                        r -> r, (a, b) -> b));
        this.lastRefreshAt = Instant.now();
        this.everSucceeded = true;
    }

    /** The registration for one node id (case-insensitive), or {@code null} if unregistered. */
    @Nullable
    public Registration registrationFor(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return byNodeId.get(nodeId.toLowerCase());
    }

    /** Every currently-known registration whose owner localpart matches (case-sensitive — localparts
     *  are already canonical/lowercase-folded upstream). */
    public List<Registration> byLocalpart(String localpart) {
        if (localpart == null || localpart.isBlank()) {
            return List.of();
        }
        return byNodeId.values().stream()
                .filter(r -> localpart.equals(r.localpart()))
                .toList();
    }

    public boolean everSucceeded() {
        return everSucceeded;
    }

    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    /** Test seam — prime the snapshot without a live user-bff. */
    public void primeForTest(List<Registration> rows) {
        swap(rows);
    }
}
