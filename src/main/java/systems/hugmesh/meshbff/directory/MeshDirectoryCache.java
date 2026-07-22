package systems.hugmesh.meshbff.directory;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The node/room directory cache — cloned from hugmesh-gateway's
 * {@code cache.GatewayRouteAccessService} shape: a {@code volatile} snapshot,
 * seeded empty at {@code @PostConstruct}, refreshed on a
 * {@code @Scheduled(fixedDelayString=...)} tick from Postgres (the source of
 * truth), best-effort mirrored to Cassandra on every successful reload, and
 * falling back to the Cassandra mirror ({@code switchIfEmpty}) ONLY when the
 * Postgres read itself fails — all guarded by a
 * {@link ReactiveCircuitBreaker} so a stalled datastore never blocks a reload
 * tick indefinitely.
 *
 * <p>Every {@code GET /bff/mesh/**} read (nodes/rooms/status) is served from
 * this in-memory snapshot — NEVER a per-request Postgres/Cassandra query — so
 * request latency is independent of datastore health once the cache has been
 * seeded at least once.
 */
@Service
public class MeshDirectoryCache {

    private static final Logger log = LoggerFactory.getLogger(MeshDirectoryCache.class);

    private record Snapshot(List<MeshNodeRow> nodes, List<MeshRoomRow> rooms) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of());
    }

    private final MeshNodeRepository nodeRepository;
    private final MeshRoomRepository roomRepository;
    private final DirectoryMirror mirror;
    private final Duration onlineWindow;
    @Nullable
    private final ReactiveCircuitBreaker refreshBreaker;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile Instant lastRefreshAt = Instant.EPOCH;
    private volatile String source = "seed";

    public MeshDirectoryCache(
            MeshNodeRepository nodeRepository,
            MeshRoomRepository roomRepository,
            DirectoryMirror mirror,
            @Value("${app.mesh.node-online-window-seconds:300}") long onlineWindowSeconds,
            @Nullable ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.nodeRepository = nodeRepository;
        this.roomRepository = roomRepository;
        this.mirror = mirror;
        this.onlineWindow = Duration.ofSeconds(onlineWindowSeconds);
        this.refreshBreaker = circuitBreakerFactory != null
                ? circuitBreakerFactory.create("meshDirectory")
                : null;
    }

    @PostConstruct
    void init() {
        refreshNow().subscribe();
    }

    @Scheduled(fixedDelayString = "${app.mesh.cache.refresh-interval-ms:5000}")
    void refresh() {
        refreshNow().subscribe();
    }

    private Mono<Void> refreshNow() {
        Mono<Snapshot> fromPostgres = guarded(loadFromPostgres())
                .doOnNext(loaded -> {
                    swap(loaded, "postgres");
                    mirrorToCassandra(loaded).subscribe();
                })
                .onErrorResume(e -> {
                    log.warn("[mesh-directory] Postgres load failed ({}), trying the Cassandra mirror",
                            e.toString());
                    return Mono.empty();
                });

        Mono<Snapshot> fromCassandra = guarded(loadFromCassandra())
                .doOnNext(loaded -> swap(loaded, "cassandra"))
                .onErrorResume(e -> {
                    log.warn("[mesh-directory] Cassandra mirror load failed ({}), keeping current snapshot",
                            e.toString());
                    return Mono.empty();
                });

        return fromPostgres.switchIfEmpty(fromCassandra).then();
    }

    private Mono<Snapshot> guarded(Mono<Snapshot> read) {
        if (refreshBreaker == null) {
            return read;
        }
        return refreshBreaker.run(read, t -> {
            log.warn("[mesh-directory] meshDirectory breaker fast-failed a refresh read: {}", t.toString());
            return Mono.empty();
        });
    }

    private void swap(Snapshot next, String src) {
        this.snapshot = next;
        this.lastRefreshAt = Instant.now();
        this.source = src;
    }

    private Mono<Snapshot> loadFromPostgres() {
        return Mono.zip(
                nodeRepository.findAll().collectList(),
                roomRepository.findAll().collectList(),
                Snapshot::new);
    }

    private Mono<Snapshot> loadFromCassandra() {
        return Mono.zip(
                mirror.loadNodes().collectList(),
                mirror.loadRooms().collectList(),
                Snapshot::new);
    }

    private Mono<Void> mirrorToCassandra(Snapshot loaded) {
        Mono<Void> nodes = Flux.fromIterable(loaded.nodes())
                .concatMap(mirror::upsertNode)
                .then();
        Mono<Void> rooms = Flux.fromIterable(loaded.rooms())
                .concatMap(mirror::upsertRoom)
                .then();
        return nodes.then(rooms)
                .onErrorResume(e -> {
                    log.warn("[mesh-directory] Cassandra mirror write-through failed (cache may lag): {}",
                            e.toString());
                    return Mono.empty();
                });
    }

    // ── Read surface (in-memory snapshot only) ───────────────────────────────

    public List<MeshNodeRow> nodesSnapshot() {
        return snapshot.nodes();
    }

    public List<MeshRoomRow> roomsSnapshot() {
        return snapshot.rooms();
    }

    public Optional<MeshNodeRow> node(String nodeId) {
        return snapshot.nodes().stream().filter(n -> n.nodeId().equalsIgnoreCase(nodeId)).findFirst();
    }

    /** Whether a node's last-seen timestamp falls within the online window (default 300s). */
    public boolean isOnline(MeshNodeRow node) {
        if (node.lastSeenAt() == null) {
            return false;
        }
        return !node.lastSeenAt().isBefore(Instant.now().minus(onlineWindow));
    }

    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    public String source() {
        return source;
    }

    /** Test seam — prime the snapshot without a live datastore. */
    public void primeForTest(List<MeshNodeRow> nodes, List<MeshRoomRow> rooms) {
        this.snapshot = new Snapshot(List.copyOf(nodes), List.copyOf(rooms));
        this.source = "test";
    }
}
