package net.hugmesh.meshbff.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The meshbridge daemon's {@code POST /bff/mesh/internal/bridge-sync} push
 * body. Field names match {@code MeshBffSync.push_snapshot}'s payload in
 * {@code specs/cluster/manifests/meshbridge/app/main.py} EXACTLY (verified
 * against that file's {@code sync_loop}, which builds {@code nodes}/{@code rooms}
 * from {@code bridge.node_db}/{@code bridge.mappings}):
 *
 * <pre>{"nodes": [{"nodeId","longName","shortName","lastSeenAt","batteryPct","snr","hopsAway","viaDevice"}, ...],
 *  "rooms": [{"roomId","device","meshChannel"}, ...]}</pre>
 */
public record BridgeSyncRequest(List<BridgeNode> nodes, List<BridgeRoom> rooms) {

    /** Defensive, unmodifiable copy on the way in; null stays null (the service treats it as empty). */
    public BridgeSyncRequest {
        nodes = nodes == null ? null : Collections.unmodifiableList(new ArrayList<>(nodes));
        rooms = rooms == null ? null : Collections.unmodifiableList(new ArrayList<>(rooms));
    }

    /** Unmodifiable view; the record never hands out its backing list. */
    @Override
    public List<BridgeNode> nodes() {
        return nodes == null ? null : Collections.unmodifiableList(nodes);
    }

    /** Unmodifiable view; the record never hands out its backing list. */
    @Override
    public List<BridgeRoom> rooms() {
        return rooms == null ? null : Collections.unmodifiableList(rooms);
    }

    /** One node's telemetry snapshot, in the bridge's OWN field-name/casing. */
    public record BridgeNode(
            String nodeId,
            String longName,
            String shortName,
            Instant lastSeenAt,
            Integer batteryPct,
            Float snr,
            Integer hopsAway,
            String viaDevice) {
    }

    /** One room->device/channel mapping, in the bridge's OWN field-name/casing. */
    public record BridgeRoom(String roomId, String device, Integer meshChannel) {
    }
}
