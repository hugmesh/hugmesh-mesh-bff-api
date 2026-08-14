package net.hugmesh.meshbff.directory;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Explicit {@code INSERT ... ON CONFLICT DO UPDATE} upserts for
 * {@code mesh_node_directory} / {@code mesh_room} — the write side the
 * bridge-sync ingest ({@code sync.BridgeSyncService}) uses. Deliberately NOT
 * {@link MeshNodeRepository}{@code .save()} / {@link MeshRoomRepository}{@code
 * .save()}: both tables use an APP-ASSIGNED (not database-generated) String
 * primary key, and Spring Data R2DBC's default {@code isNew()} heuristic treats
 * a non-null assigned id as "existing" — a plain {@code save()} on a genuinely
 * new row would therefore issue a no-op {@code UPDATE ... WHERE id = ?} that
 * matches zero rows, silently dropping the insert. Raw upsert SQL sidesteps the
 * heuristic entirely and is also the natural shape for a repeatedly-pushed
 * snapshot (the bridge re-sends every node/room it currently knows about on
 * every tick, not just deltas).
 *
 * <p>Data-access standard: this class is a thin FACADE over
 * {@link MeshNodeDirectoryR2dbcRepository} / {@link MeshRoomDirectoryR2dbcRepository}
 * — no raw {@code DatabaseClient} query strings. Each upsert is a Spring Data
 * {@code @Query} method over a {@code static final String} SQL constant (derivation
 * cannot express an {@code ON CONFLICT DO UPDATE} upsert), matching the
 * {@code ModActionR2dbcRepository} idiom. The facade preserves the original
 * {@code MeshNodeRow} / {@code MeshRoomRow} parameter shape so
 * {@code sync.BridgeSyncService} is unchanged.
 */
@Component
public class MeshNodeDirectoryDao {

    private final MeshNodeDirectoryR2dbcRepository nodeUpserts;
    private final MeshRoomDirectoryR2dbcRepository roomUpserts;

    public MeshNodeDirectoryDao(MeshNodeDirectoryR2dbcRepository nodeUpserts,
                                MeshRoomDirectoryR2dbcRepository roomUpserts) {
        this.nodeUpserts = nodeUpserts;
        this.roomUpserts = roomUpserts;
    }

    /** Upsert one node row (validated/clamped by the caller — see BridgeSyncService). */
    public Mono<Void> upsertNode(MeshNodeRow row) {
        return nodeUpserts.upsertNode(row.nodeId(), row.longName(), row.shortName(), row.lastSeenAt(),
                row.batteryPct(), row.snr(), row.hopsAway(), row.viaDevice(), domainOrDefault(row.domain()));
    }

    /** Upsert one room row. */
    public Mono<Void> upsertRoom(MeshRoomRow row) {
        return roomUpserts.upsertRoom(row.roomId(), row.device(), row.meshChannel(),
                domainOrDefault(row.domain()));
    }

    private static String domainOrDefault(String domain) {
        return domain == null || domain.isBlank() ? "default" : domain;
    }
}
