package net.hugmesh.meshbff.directory;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Write-side repository for {@code mesh_node_directory} — the explicit upsert
 * {@link MeshNodeDirectoryDao} uses (see its javadoc for why this can't be a plain
 * {@code save()}: the app-assigned {@code node_id} primary key defeats Spring Data
 * R2DBC's default {@code isNew()} heuristic). Deliberately extends the bare
 * {@link Repository} marker -- NOT {@code ReactiveCrudRepository} -- so this interface
 * exposes ONLY {@link #upsertNode}: no inherited {@code save()}/{@code deleteById()}
 * surface that a future caller could reach for and reintroduce the exact silent-drop
 * bug this class exists to avoid. Reads still go through {@link MeshNodeRepository}.
 *
 * <p>Per the org-wide data-access standard (see {@code ModActionR2dbcRepository}):
 * derivation cannot express an {@code INSERT ... ON CONFLICT DO UPDATE} upsert, so
 * this is an explicit {@code @Query} over a {@code static final String} SQL constant.
 */
public interface MeshNodeDirectoryR2dbcRepository extends Repository<MeshNodeRow, String> {

    /** The upsert statement: insert-or-refresh one node's telemetry row. {@code updated_at}
     *  is always stamped {@code now()}; {@code last_seen_at} keeps the existing value when the
     *  incoming one is {@code NULL} (a bridge tick that heard the node but not its GPS/battery
     *  frame must not blank out a previously-known {@code last_seen_at}). */
    String UPSERT_NODE = """
            INSERT INTO mesh_node_directory \
                (node_id, long_name, short_name, last_seen_at, battery_pct, snr, \
                 hops_away, via_device, domain, updated_at) \
            VALUES (:nodeId, :longName, :shortName, :lastSeenAt, :batteryPct, :snr, \
                    :hopsAway, :viaDevice, :domain, now()) \
            ON CONFLICT (node_id) DO UPDATE SET \
                long_name = EXCLUDED.long_name, \
                short_name = EXCLUDED.short_name, \
                last_seen_at = COALESCE(EXCLUDED.last_seen_at, mesh_node_directory.last_seen_at), \
                battery_pct = EXCLUDED.battery_pct, \
                snr = EXCLUDED.snr, \
                hops_away = EXCLUDED.hops_away, \
                via_device = EXCLUDED.via_device, \
                updated_at = now()""";

    /** Insert-or-refresh one node row. Null-valued params (e.g. an unadvertised
     *  {@code longName}) bind as SQL {@code NULL} automatically -- Spring Data resolves the
     *  R2DBC type hint from each parameter's declared Java type, so no manual
     *  {@code bindNull(name, Class)} bookkeeping is needed here (unlike the raw
     *  {@code DatabaseClient} this replaces). */
    @Modifying
    @Query(UPSERT_NODE)
    Mono<Void> upsertNode(@Param("nodeId") String nodeId,
                          @Param("longName") String longName,
                          @Param("shortName") String shortName,
                          @Param("lastSeenAt") Instant lastSeenAt,
                          @Param("batteryPct") Integer batteryPct,
                          @Param("snr") Float snr,
                          @Param("hopsAway") Integer hopsAway,
                          @Param("viaDevice") String viaDevice,
                          @Param("domain") String domain);
}
