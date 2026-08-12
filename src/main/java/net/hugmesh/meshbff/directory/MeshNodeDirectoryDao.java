package net.hugmesh.meshbff.directory;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

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
 */
@Component
public class MeshNodeDirectoryDao {

    private final DatabaseClient db;

    public MeshNodeDirectoryDao(DatabaseClient db) {
        this.db = db;
    }

    /** Upsert one node row (validated/clamped by the caller — see BridgeSyncService). */
    public Mono<Void> upsertNode(MeshNodeRow row) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO mesh_node_directory
                            (node_id, long_name, short_name, last_seen_at, battery_pct, snr,
                             hops_away, via_device, domain, updated_at)
                        VALUES (:nodeId, :longName, :shortName, :lastSeenAt, :batteryPct, :snr,
                                :hopsAway, :viaDevice, :domain, now())
                        ON CONFLICT (node_id) DO UPDATE SET
                            long_name = EXCLUDED.long_name,
                            short_name = EXCLUDED.short_name,
                            last_seen_at = COALESCE(EXCLUDED.last_seen_at, mesh_node_directory.last_seen_at),
                            battery_pct = EXCLUDED.battery_pct,
                            snr = EXCLUDED.snr,
                            hops_away = EXCLUDED.hops_away,
                            via_device = EXCLUDED.via_device,
                            updated_at = now()
                        """)
                .bind("nodeId", row.nodeId());
        spec = bindOrNull(spec, "longName", row.longName(), String.class);
        spec = bindOrNull(spec, "shortName", row.shortName(), String.class);
        spec = bindOrNull(spec, "lastSeenAt", row.lastSeenAt(), Instant.class);
        spec = bindOrNull(spec, "batteryPct", row.batteryPct(), Integer.class);
        spec = bindOrNull(spec, "snr", row.snr(), Float.class);
        spec = bindOrNull(spec, "hopsAway", row.hopsAway(), Integer.class);
        spec = bindOrNull(spec, "viaDevice", row.viaDevice(), String.class);
        spec = spec.bind("domain", row.domain() == null || row.domain().isBlank() ? "default" : row.domain());
        return spec.then();
    }

    /** Upsert one room row. */
    public Mono<Void> upsertRoom(MeshRoomRow row) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO mesh_room (room_id, device, mesh_channel, domain, updated_at)
                        VALUES (:roomId, :device, :meshChannel, :domain, now())
                        ON CONFLICT (room_id) DO UPDATE SET
                            device = EXCLUDED.device,
                            mesh_channel = EXCLUDED.mesh_channel,
                            updated_at = now()
                        """)
                .bind("roomId", row.roomId());
        spec = bindOrNull(spec, "device", row.device(), String.class);
        spec = bindOrNull(spec, "meshChannel", row.meshChannel(), Integer.class);
        spec = spec.bind("domain", row.domain() == null || row.domain().isBlank() ? "default" : row.domain());
        return spec.then();
    }

    private static DatabaseClient.GenericExecuteSpec bindOrNull(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
