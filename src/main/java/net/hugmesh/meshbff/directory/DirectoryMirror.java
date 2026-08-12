package net.hugmesh.meshbff.directory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The best-effort Cassandra write-through mirror + fallback-read seam for the
 * node/room directory — mirrors {@code hugmesh-admin-bff-api}'s
 * {@code routeaccess.RouteMirror} shape. {@link MeshDirectoryCache} writes here
 * (fire-and-forget, best-effort) after every successful Postgres reload, and
 * reads here ONLY when the Postgres reload itself fails (the
 * {@code switchIfEmpty} fallback leg).
 *
 * <p>Two implementations: {@link CassandraDirectoryMirror} (real, gated on
 * {@code app.mirror.cassandra.enabled=true}) and {@link NoopDirectoryMirror}
 * (the default — wins via {@code @ConditionalOnMissingBean} whenever Cassandra
 * is not configured, mirroring hugmesh-federation-api's
 * {@code NoopManifestCache} inversion).
 */
public interface DirectoryMirror {

    Mono<Void> upsertNode(MeshNodeRow row);

    Mono<Void> upsertRoom(MeshRoomRow row);

    Flux<MeshNodeRow> loadNodes();

    Flux<MeshRoomRow> loadRooms();
}
