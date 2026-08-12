package net.hugmesh.meshbff.directory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A row of {@code mesh_room} — one Matrix room the bridge has mapped to a
 * Meshtastic device/channel pair (mirrors meshbridge's own in-memory
 * {@code bridge.mappings}).
 *
 * @param roomId      the Matrix room id the bridge maintains this mapping for.
 * @param device      which configured bridge device link serves this room.
 * @param meshChannel the Meshtastic channel index the room is bound to.
 * @param domain      brand/tenant scope; {@code "default"} on a single-tenant cluster.
 * @param updatedAt   row last-write timestamp (DB-managed).
 */
@Table("mesh_room")
public record MeshRoomRow(
        @Id String roomId,
        String device,
        Integer meshChannel,
        String domain,
        Instant updatedAt) {
}
