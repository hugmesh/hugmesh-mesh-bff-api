package net.hugmesh.meshbff.sync;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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

    /**
     * Copies both lists on the way in, so this record neither keeps a list its
     * caller can still change nor hands a changeable one back from
     * {@link #nodes()} / {@link #rooms()}.
     *
     * <p>A {@code null} LIST stays {@code null}. The bridge may leave either field
     * out, {@link BridgeSyncController} builds {@code new BridgeSyncRequest(null, null)}
     * for an empty body, and {@link BridgeSyncService#apply} already reads
     * {@code null} as "no rows". A bare {@link List#copyOf} throws on {@code null},
     * which would turn an omitted field into a failed request.
     *
     * <p>A {@code null} ROW is dropped. {@link List#copyOf} rejects null elements
     * too, and throwing here would fail the whole push while its JSON is being
     * decoded. The ingest contract is the opposite: a malformed row is skipped and
     * the rest of the snapshot is applied ({@link BridgeSyncService}).
     *
     * <p>Keep the outer {@link List#copyOf}; it is not redundant with
     * {@code toList()}. SpotBugs clears EI_EXPOSE_REP on the accessors only when it
     * can see the field assigned from a call it knows returns an immutable
     * collection, and {@code Stream.toList()} is not on that list (measured on
     * spotbugs 4.9.8 with the hugmesh-build-config 0.5.0 filters: without the
     * wrapper both accessors are reported again).
     */
    public BridgeSyncRequest {
        nodes = nodes == null ? null : List.copyOf(nodes.stream().filter(Objects::nonNull).toList());
        rooms = rooms == null ? null : List.copyOf(rooms.stream().filter(Objects::nonNull).toList());
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
