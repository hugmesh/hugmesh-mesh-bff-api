package systems.snapdragon.meshbff.sync;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * {@code POST /bff/mesh/internal/bridge-sync} — the meshbridge daemon's
 * OPTIONAL periodic push of its full node-directory + mesh-room snapshot
 * ({@code MeshBffSync.push_snapshot} in
 * {@code specs/cluster/manifests/meshbridge/app/main.py}).
 *
 * <p><b>SERVICE-PRINCIPAL ONLY.</b> This sits under {@code /bff/mesh/internal/**},
 * which {@code SecurityConfig} gates to {@code hasRole('SERVICE')} EXCLUSIVELY
 * (the role hierarchy has no edge to SERVICE — not even SUPERADMIN reaches it);
 * the {@code @PreAuthorize} here is the method-level defence-in-depth, mirroring
 * user-bff-api's {@code InternalMeshDeviceController} posture exactly.
 */
@Tag(name = "Bridge sync (internal)", description = "SERVICE-PRINCIPAL ONLY: the meshbridge daemon's periodic node/room snapshot push.")
@RestController
@RequestMapping("/bff/mesh/internal")
@PreAuthorize("hasRole('SERVICE')")
public class BridgeSyncController {

    private final BridgeSyncService service;

    public BridgeSyncController(BridgeSyncService service) {
        this.service = service;
    }

    @Operation(summary = "Upsert the bridge's full node/room snapshot (tolerant/clamped; "
            + "malformed rows are skipped, never a 4xx for the whole request).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Snapshot applied"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_SERVICE", content = @Content)
    })
    @PostMapping("/bridge-sync")
    public Mono<Map<String, Object>> bridgeSync(@RequestBody BridgeSyncRequest body) {
        return service.apply(body == null ? new BridgeSyncRequest(null, null) : body)
                .map(r -> Map.of("nodesUpserted", r.nodesUpserted(), "roomsUpserted", r.roomsUpserted()));
    }
}
