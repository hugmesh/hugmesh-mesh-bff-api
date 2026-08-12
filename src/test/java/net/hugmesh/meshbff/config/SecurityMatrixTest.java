package net.hugmesh.meshbff.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SECURITY-MATRIX slice — a full-context boot (the SAME wiring
 * {@link net.hugmesh.meshbff.OpenApiSpecDumpTest} already proves works,
 * {@code test} profile: no real Postgres/Cassandra/user-bff dialed) exercising
 * the REAL {@link SecurityConfig} {@code authorizeExchange} policy end to end
 * over real HTTP, with a mocked {@link ReactiveJwtDecoder} standing in for
 * Casdoor (each test token is registered against a canned {@link Jwt}, so the
 * REAL {@code CasdoorAuthorities} converter still runs and does the actual
 * claims-&gt;authorities work — only the network hop to Casdoor is stubbed).
 *
 * <p>Table asserted:
 * <ul>
 *   <li><b>ROLE_MESH</b> reaches {@code /bff/mesh/nodes}, {@code /bff/mesh/rooms},
 *       {@code /bff/mesh/status/{id}} (404 — empty cache, no seeded node),
 *       {@code /bff/mesh/devices/mine}; is FORBIDDEN on
 *       {@code /bff/mesh/internal/bridge-sync}.</li>
 *   <li><b>ROLE_ADMIN</b> / <b>ROLE_SUPERADMIN</b> reach the same {@code /bff/mesh/**}
 *       surface (the coarse chain-level superset pairing) but are ALSO FORBIDDEN
 *       on {@code /bff/mesh/internal/**} — the role hierarchy has NO edge to
 *       SERVICE, so even SUPERADMIN is denied (mirrors user-bff-api's
 *       {@code InternalMeshDeviceController} posture).</li>
 *   <li><b>ROLE_SERVICE</b> reaches {@code /bff/mesh/internal/bridge-sync} but is
 *       FORBIDDEN on the ordinary {@code /bff/mesh/**} surface (no MESH/ADMIN/
 *       SUPERADMIN authority).</li>
 *   <li><b>plain ROLE_USER</b> is FORBIDDEN everywhere under {@code /bff/mesh/**}.</li>
 *   <li><b>anonymous</b> is UNAUTHORIZED (401) everywhere under {@code /bff/mesh/**},
 *       and ALLOWED (not 401/403) on the actuator health/info probes.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class SecurityMatrixTest {

    @Autowired
    WebTestClient client;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    private WebTestClient.RequestHeadersSpec<?> get(String path, String... roleNames) {
        String token = "tok-" + String.join("-", roleNames);
        stub(token, roleNames, null);
        return client.get().uri(path).header("Authorization", "Bearer " + token);
    }

    private WebTestClient.RequestHeadersSpec<?> getWithLocalpart(String path, String localpart, String... roleNames) {
        String token = "tok-lp-" + localpart + "-" + String.join("-", roleNames);
        stub(token, roleNames, localpart);
        return client.get().uri(path).header("Authorization", "Bearer " + token);
    }

    private WebTestClient.RequestBodySpec post(String path, String... roleNames) {
        String token = "tok-post-" + String.join("-", roleNames);
        stub(token, roleNames, null);
        return client.post().uri(path).header("Authorization", "Bearer " + token);
    }

    private void stub(String token, String[] roleNames, String localpart) {
        List<Map<String, String>> roles = List.of(roleNames).stream().map(r -> Map.of("name", r)).toList();
        Jwt.Builder builder = Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "test-subject")
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (localpart != null) {
            builder.claim("localpart", localpart);
        }
        when(jwtDecoder.decode(eq(token))).thenReturn(Mono.just(builder.build()));
    }

    // ── ROLE_MESH — the ordinary floor ────────────────────────────────────────

    @Test
    void mesh_allowedOn_nodes() {
        get("/bff/mesh/nodes", "mesh").exchange().expectStatus().isOk();
    }

    @Test
    void mesh_allowedOn_rooms() {
        get("/bff/mesh/rooms", "mesh").exchange().expectStatus().isOk();
    }

    @Test
    void mesh_notFoundOn_unknownStatus() {
        // Authorized, but the in-memory cache is empty in this profile — 404, never
        // 401/403, proving the caller passed the auth gate.
        get("/bff/mesh/status/!deadbeef", "mesh").exchange().expectStatus().isNotFound();
    }

    @Test
    void mesh_allowedOn_devicesMine() {
        getWithLocalpart("/bff/mesh/devices/mine", "alice", "mesh").exchange().expectStatus().isOk();
    }

    @Test
    void mesh_forbiddenOn_internalBridgeSync() {
        post("/bff/mesh/internal/bridge-sync", "mesh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
    }

    // ── ROLE_ADMIN / ROLE_SUPERADMIN — the coarse-chain supersets ─────────────

    @Test
    void admin_allowedOn_nodes() {
        get("/bff/mesh/nodes", "admin").exchange().expectStatus().isOk();
    }

    @Test
    void superadmin_allowedOn_rooms() {
        get("/bff/mesh/rooms", "superadmin").exchange().expectStatus().isOk();
    }

    @Test
    void admin_forbiddenOn_internalBridgeSync() {
        // No edge to SERVICE in the role hierarchy — even ADMIN is denied.
        post("/bff/mesh/internal/bridge-sync", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void superadmin_forbiddenOn_internalBridgeSync() {
        // No edge to SERVICE in the role hierarchy — even SUPERADMIN is denied.
        post("/bff/mesh/internal/bridge-sync", "superadmin")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
    }

    // ── ROLE_SERVICE — the internal-only floor ────────────────────────────────

    @Test
    void service_allowedOn_internalBridgeSync() {
        post("/bff/mesh/internal/bridge-sync", "service")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isOk();
    }

    @Test
    void service_forbiddenOn_ordinaryMeshSurface() {
        // ROLE_SERVICE holds no MESH/ADMIN/SUPERADMIN authority.
        get("/bff/mesh/nodes", "service").exchange().expectStatus().isForbidden();
    }

    // ── plain ROLE_USER — denied everywhere under /bff/mesh/** ────────────────

    @Test
    void user_forbiddenOn_nodes() {
        get("/bff/mesh/nodes", "user").exchange().expectStatus().isForbidden();
    }

    @Test
    void user_forbiddenOn_internalBridgeSync() {
        post("/bff/mesh/internal/bridge-sync", "user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
    }

    // ── anonymous — 401 under /bff/mesh/**, allowed on health/info ────────────

    @Test
    void anonymous_unauthorizedOn_nodes() {
        client.get().uri("/bff/mesh/nodes").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymous_unauthorizedOn_internalBridgeSync() {
        client.post().uri("/bff/mesh/internal/bridge-sync")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymous_allowedOn_healthAndInfoProbes() {
        for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/actuator/info")) {
            client.get().uri(path).exchange().expectStatus().value(status -> {
                if (status == 401 || status == 403) {
                    throw new AssertionError("expected anonymous ALLOW on " + path + " but got " + status);
                }
            });
        }
    }
}
