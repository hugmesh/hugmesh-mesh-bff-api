package systems.hugmesh.meshbff.directory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A row of {@code mesh_node_directory} — one Meshtastic radio's last-known
 * telemetry, independent of whether it is claimed/registered by a platform
 * user (that mapping lives in user-bff's {@code mesh_device} table, joined at
 * read time via {@link systems.hugmesh.meshbff.devices.RegistrationCache}).
 *
 * @param nodeId     canonical form {@code "!"+8 lowercase hex} (e.g. {@code !a1b2c3d4}).
 * @param longName   the radio's user-set long name (nullable — not every node advertises one).
 * @param shortName  the radio's 1-4 char short name (nullable).
 * @param lastSeenAt last telemetry timestamp the bridge observed for this node.
 * @param batteryPct 0-100, or 101 = externally powered (meshbridge/user-bff convention); nullable.
 * @param snr        last signal-to-noise ratio heard; nullable.
 * @param hopsAway   mesh hop count from the bridge's radio; nullable.
 * @param viaDevice  which configured bridge device link last heard this node; nullable.
 * @param domain     brand/tenant scope; {@code "default"} on a single-tenant cluster.
 * @param updatedAt  row last-write timestamp (DB-managed).
 */
@Table("mesh_node_directory")
public record MeshNodeRow(
        @Id String nodeId,
        String longName,
        String shortName,
        Instant lastSeenAt,
        Integer batteryPct,
        Float snr,
        Integer hopsAway,
        String viaDevice,
        String domain,
        Instant updatedAt) {
}
