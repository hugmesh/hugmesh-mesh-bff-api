package net.hugmesh.meshbff.directory;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

/**
 * Write-side repository for {@code mesh_room} -- the explicit upsert
 * {@link MeshNodeDirectoryDao} uses. See {@link MeshNodeDirectoryR2dbcRepository}'s
 * javadoc for why this extends the bare {@link Repository} marker (no inherited
 * {@code save()}) and why the upsert is an explicit {@code @Query} constant rather
 * than a derived method. Reads still go through {@link MeshRoomRepository}.
 */
public interface MeshRoomDirectoryR2dbcRepository extends Repository<MeshRoomRow, String> {

    /** The upsert statement: insert-or-refresh one room-to-device/channel mapping row. */
    String UPSERT_ROOM = """
            INSERT INTO mesh_room (room_id, device, mesh_channel, domain, updated_at) \
            VALUES (:roomId, :device, :meshChannel, :domain, now()) \
            ON CONFLICT (room_id) DO UPDATE SET \
                device = EXCLUDED.device, \
                mesh_channel = EXCLUDED.mesh_channel, \
                updated_at = now()""";

    /** Insert-or-refresh one room row. Null-valued params (e.g. an unmapped
     *  {@code meshChannel}) bind as SQL {@code NULL} automatically -- see
     *  {@link MeshNodeDirectoryR2dbcRepository#upsertNode} for why no manual
     *  {@code bindNull} bookkeeping is needed here. */
    @Modifying
    @Query(UPSERT_ROOM)
    Mono<Void> upsertRoom(@Param("roomId") String roomId,
                          @Param("device") String device,
                          @Param("meshChannel") Integer meshChannel,
                          @Param("domain") String domain);
}
