package systems.hugmesh.meshbff.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.meshbff.directory.MeshNodeDirectoryDao;
import systems.hugmesh.meshbff.directory.MeshNodeRow;
import systems.hugmesh.meshbff.directory.MeshRoomRow;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates/clamps + upserts the meshbridge daemon's periodic bridge-sync
 * snapshot ({@code POST /bff/mesh/internal/bridge-sync}). TOLERANT parsing —
 * the bridge is a best-effort, fire-and-forget pusher (it never retries a
 * single row, only the whole snapshot on the next 60s tick), so a malformed
 * ROW is skipped (never fails the whole request) and a malformed VALUE is
 * clamped rather than rejected wherever a sane clamp exists.
 */
@Service
public class BridgeSyncService {

    private static final Logger log = LoggerFactory.getLogger(BridgeSyncService.class);

    /** Canonical node id shape: {@code "!"} + 8 lowercase hex digits. */
    private static final Pattern NODE_ID = Pattern.compile("^![0-9a-f]{8}$");

    private final MeshNodeDirectoryDao dao;

    public BridgeSyncService(MeshNodeDirectoryDao dao) {
        this.dao = dao;
    }

    public record Result(int nodesUpserted, int roomsUpserted) {
    }

    public Mono<Result> apply(BridgeSyncRequest request) {
        List<BridgeSyncRequest.BridgeNode> nodes = request.nodes() == null ? List.of() : request.nodes();
        List<BridgeSyncRequest.BridgeRoom> rooms = request.rooms() == null ? List.of() : request.rooms();

        // handle(), not map()+filter(): Reactor's map() forbids a null return (it throws
        // NPE immediately, before any downstream filter runs) — normalizeNode/normalizeRoom
        // legitimately return null for an unusable row, so handle()'s sink-based "emit
        // nothing for this element" is the correct null-tolerant operator here.
        Mono<Integer> nodeCount = Flux.fromIterable(nodes)
                .<MeshNodeRow>handle((n, sink) -> {
                    MeshNodeRow row = normalizeNode(n);
                    if (row != null) {
                        sink.next(row);
                    }
                })
                .concatMap(row -> dao.upsertNode(row).thenReturn(1))
                .reduce(0, Integer::sum);

        Mono<Integer> roomCount = Flux.fromIterable(rooms)
                .<MeshRoomRow>handle((r, sink) -> {
                    MeshRoomRow row = normalizeRoom(r);
                    if (row != null) {
                        sink.next(row);
                    }
                })
                .concatMap(row -> dao.upsertRoom(row).thenReturn(1))
                .reduce(0, Integer::sum);

        return Mono.zip(nodeCount, roomCount, Result::new);
    }

    /** Normalize + validate one bridge node; {@code null} means "skip, unusable". */
    private static MeshNodeRow normalizeNode(BridgeSyncRequest.BridgeNode n) {
        String nodeId = normalizeNodeId(n.nodeId());
        if (nodeId == null) {
            log.warn("[bridge-sync] skipping row with unparseable nodeId: {}", n.nodeId());
            return null;
        }
        return new MeshNodeRow(nodeId, trimToNull(n.longName()), trimToNull(n.shortName()),
                n.lastSeenAt(), clampBattery(n.batteryPct()), n.snr(), n.hopsAway(),
                trimToNull(n.viaDevice()), "default", null);
    }

    /** Normalize + validate one bridge room; {@code null} means "skip, unusable". */
    private static MeshRoomRow normalizeRoom(BridgeSyncRequest.BridgeRoom r) {
        String roomId = trimToNull(r.roomId());
        if (roomId == null) {
            log.warn("[bridge-sync] skipping room row with a blank roomId");
            return null;
        }
        return new MeshRoomRow(roomId, trimToNull(r.device()), r.meshChannel(), "default", null);
    }

    /**
     * Tolerant node-id normalization: accepts a leading {@code !} or not, and
     * any hex case, and folds to the canonical {@code "!"+8-lowercase-hex}
     * form. Returns {@code null} when the result still does not match
     * {@link #NODE_ID} (e.g. wrong length, non-hex characters).
     */
    static String normalizeNodeId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (!s.startsWith("!")) {
            s = "!" + s;
        }
        return NODE_ID.matcher(s).matches() ? s : null;
    }

    /** Clamp to the documented 0..101 range (101 = externally powered); {@code null} passes through. */
    static Integer clampBattery(Integer pct) {
        if (pct == null) {
            return null;
        }
        return Math.max(0, Math.min(101, pct));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
