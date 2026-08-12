package net.hugmesh.meshbff.devices;

import net.hugmesh.meshbff.directory.MeshNodeRow;

import java.time.Instant;

/**
 * The {@code GET /bff/mesh/devices/mine} shape — one of the CALLER's own
 * registered devices (per user-bff), enriched with live status from
 * {@code directory.MeshDirectoryCache} when this BFF has heard the node
 * (fail-soft: a node the directory has never seen still appears, with every
 * status field {@code null} — a caller's own device list is never hidden by a
 * directory cache miss).
 */
public record MyDeviceView(
        String nodeId,
        String displayName,
        String visibility,
        Instant lastSeenAt,
        Integer batteryPct,
        Float snr,
        Integer hopsAway,
        String viaDevice,
        boolean online) {

    public static MyDeviceView of(Registration reg, MeshNodeRow liveRow, boolean online) {
        if (liveRow == null) {
            return new MyDeviceView(reg.nodeId(), reg.displayName(), reg.visibility(),
                    null, null, null, null, null, false);
        }
        return new MyDeviceView(reg.nodeId(), reg.displayName(), reg.visibility(),
                liveRow.lastSeenAt(), liveRow.batteryPct(), liveRow.snr(), liveRow.hopsAway(),
                liveRow.viaDevice(), online);
    }
}
