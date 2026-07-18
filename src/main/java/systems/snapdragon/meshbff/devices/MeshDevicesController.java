package systems.snapdragon.meshbff.devices;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import systems.snapdragon.meshbff.directory.MeshDirectoryCache;
import systems.snapdragon.meshbff.directory.MeshNodeRow;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /bff/mesh/devices/mine} — the CALLER's own registered devices
 * with live status: user-bff is the source of truth for "which nodes does
 * THIS caller own" (via {@link RegistrationCache}, itself refreshed from
 * user-bff's {@code GET /bff/user/internal/mesh-devices}), joined against
 * {@link MeshDirectoryCache}'s in-memory live-telemetry snapshot. No
 * visibility gating applies here — a caller always sees their OWN devices in
 * full, regardless of their own {@code visibility} setting (that setting only
 * governs what OTHER callers see, per {@code directory.Visibility}).
 */
@Tag(name = "Mesh devices (mine)", description = "The caller's own registered mesh devices, with live status.")
@RestController
@RequestMapping("/bff/mesh")
@PreAuthorize("hasAnyRole('MESH','ADMIN','SUPERADMIN')")
@Validated
public class MeshDevicesController {

    private final RegistrationCache registrations;
    private final MeshDirectoryCache directory;

    public MeshDevicesController(RegistrationCache registrations, MeshDirectoryCache directory) {
        this.registrations = registrations;
        this.directory = directory;
    }

    @Operation(summary = "The caller's own registered mesh devices (per user-bff) with live "
            + "status joined in from this BFF's directory cache when known.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Device list returned"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_MESH/ROLE_ADMIN/ROLE_SUPERADMIN", content = @Content)
    })
    @GetMapping("/devices/mine")
    public Mono<Map<String, Object>> mine(Authentication auth) {
        String localpart = localpartOf(auth);
        if (localpart == null || localpart.isBlank()) {
            // No localpart claim on this session (e.g. a non-native-login SERVICE-adjacent
            // caller) — there is no "mine" to resolve; return an empty, not an error, so a
            // caller can always safely hit this endpoint.
            return Mono.just(Map.of("devices", List.of()));
        }
        List<MyDeviceView> devices = registrations.byLocalpart(localpart).stream()
                .map(reg -> {
                    MeshNodeRow row = directory.node(reg.nodeId()).orElse(null);
                    boolean online = row != null && directory.isOnline(row);
                    return MyDeviceView.of(reg, row, online);
                })
                .toList();
        return Mono.just(Map.of("devices", devices));
    }

    private static String localpartOf(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return jwt.getClaimAsString("localpart");
    }
}
