package systems.hugmesh.meshbff.directory;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.web.ApiException;
import systems.hugmesh.meshbff.devices.RegistrationCache;
import systems.hugmesh.meshbff.security.Roles;

import java.util.List;
import java.util.Map;

/**
 * The caller-facing node/room directory reads — {@code GET /bff/mesh/nodes},
 * {@code GET /bff/mesh/rooms}, {@code GET /bff/mesh/status/{nodeId}}. Every
 * read is served from {@link MeshDirectoryCache}'s in-memory snapshot (never a
 * per-request datastore query) joined against {@link RegistrationCache}'s
 * snapshot of user-bff's registration mapping for the {@link Visibility}
 * gating — see that class's javadoc for the v1 rules.
 */
@Tag(name = "Mesh directory", description = "Cached Meshtastic node/room directory, visibility-gated attribution.")
@RestController
@RequestMapping("/bff/mesh")
@PreAuthorize("hasAnyRole('MESH','ADMIN','SUPERADMIN')")
@Validated
public class MeshDirectoryController {

    private final MeshDirectoryCache directory;
    private final RegistrationCache registrations;

    public MeshDirectoryController(MeshDirectoryCache directory, RegistrationCache registrations) {
        this.directory = directory;
        this.registrations = registrations;
    }

    @Operation(summary = "The cached mesh node directory. Anonymous nodes are always listed "
            + "without attribution; a registered node's owner localpart + live status is shown "
            + "only when the owner's visibility setting permits the caller (see the README's "
            + "Visibility v1 section).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Node directory returned"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_MESH/ROLE_ADMIN/ROLE_SUPERADMIN", content = @Content)
    })
    @GetMapping("/nodes")
    public Mono<Map<String, Object>> nodes(Authentication auth) {
        String callerLocalpart = localpartOf(auth);
        String callerDomain = Roles.domainOf(auth);
        boolean isSuperadmin = Roles.isSuperadmin(auth);
        List<MeshNodeView> views = directory.nodesSnapshot().stream()
                .map(row -> MeshNodeView.of(row, directory.isOnline(row),
                        registrations.registrationFor(row.nodeId()), callerLocalpart, callerDomain, isSuperadmin))
                .toList();
        return Mono.just(Map.of("nodes", views, "source", directory.source()));
    }

    @Operation(summary = "Mesh-enabled rooms known to the bridge, domain-scoped.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Room list returned"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_MESH/ROLE_ADMIN/ROLE_SUPERADMIN", content = @Content)
    })
    @GetMapping("/rooms")
    public Mono<Map<String, Object>> rooms(Authentication auth) {
        String callerDomain = Roles.domainOf(auth);
        boolean isSuperadmin = Roles.isSuperadmin(auth);
        List<MeshRoomView> views = directory.roomsSnapshot().stream()
                .filter(r -> Roles.domainApplies(r.domain(), callerDomain, isSuperadmin))
                .map(MeshRoomView::of)
                .toList();
        return Mono.just(Map.of("rooms", views));
    }

    @Operation(summary = "One node's status, visibility-gated exactly as GET /bff/mesh/nodes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Node status returned"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_MESH/ROLE_ADMIN/ROLE_SUPERADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Node not known to the directory", content = @Content)
    })
    @GetMapping("/status/{nodeId}")
    public Mono<MeshNodeView> status(@PathVariable String nodeId, Authentication auth) {
        return directory.node(nodeId)
                .map(row -> {
                    String callerLocalpart = localpartOf(auth);
                    String callerDomain = Roles.domainOf(auth);
                    boolean isSuperadmin = Roles.isSuperadmin(auth);
                    return MeshNodeView.of(row, directory.isOnline(row),
                            registrations.registrationFor(row.nodeId()), callerLocalpart, callerDomain, isSuperadmin);
                })
                .map(Mono::just)
                .orElseGet(() -> Mono.error(ApiException.notFound("unknown node id: " + nodeId)));
    }

    /** The caller's own canonical Matrix localpart, threaded onto the session JWT's
     *  {@code localpart} claim by the native-login session bridge (same claim
     *  {@code devices.MeshDevicesController} reads for {@code /bff/mesh/devices/mine}).
     *  {@code null} for a caller whose session carries no such claim — treated as
     *  "never the owner of anything" (fail toward hiding attribution, never leaking it). */
    static String localpartOf(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return jwt.getClaimAsString("localpart");
    }
}
