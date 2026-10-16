package net.hugmesh.meshbff.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ACTUATOR GATE — every {@code /actuator/**} path other than the public
 * {@code health}/{@code info} probes is ADMIN/SUPERADMIN-only, decided by the
 * {@link SecurityConfig} filter chain itself and NOT by the actuator exposure list.
 *
 * <p>Full-context boot over real HTTP with the REAL actuator endpoints, the exposure
 * list deliberately WIDENED to {@code *} (what an
 * {@code MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*} override does in a deployment, with no
 * code change). Without the {@code /actuator/**} rule the
 * {@code anyExchange().authenticated()} floor serves {@code /actuator/env} and friends to
 * every logged-in ROLE_USER; with it they answer 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=*")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ActuatorGateSliceTest {

    private static final List<String> GATED = List.of(
            "/actuator", "/actuator/env", "/actuator/beans", "/actuator/heapdump",
            "/actuator/metrics", "/actuator/loggers", "/actuator/threaddump",
            "/actuator/configprops", "/actuator/mappings");

    @Autowired
    WebTestClient client;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    private WebTestClient.RequestHeadersSpec<?> as(String role, String path) {
        String token = "tok-" + role;
        when(jwtDecoder.decode(eq(token))).thenReturn(Mono.just(Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "test-subject")
                .claim("roles", List.of(Map.of("name", role)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build()));
        return client.get().uri(path).header("Authorization", "Bearer " + token);
    }

    @Test
    void user_isForbiddenOn_everyGatedActuatorPath() {
        for (String path : GATED) {
            as("user", path).exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void mod_isForbiddenOn_everyGatedActuatorPath() {
        for (String path : GATED) {
            as("mod", path).exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void service_isForbiddenOn_everyGatedActuatorPath() {
        for (String path : GATED) {
            as("service", path).exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void anonymous_isUnauthorizedOn_everyGatedActuatorPath() {
        for (String path : GATED) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void admin_andSuperadmin_reachGatedActuatorPaths() {
        for (String role : List.of("admin", "superadmin")) {
            as(role, "/actuator/env").exchange().expectStatus().isOk();
            as(role, "/actuator/metrics").exchange().expectStatus().isOk();
        }
    }

    @Test
    void healthAndInfo_stayPublic() {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/info")) {
            client.get().uri(path).exchange().expectStatus().value(status ->
                    assertThat(status).as(path).isNotIn(401, 403));
        }
    }

    @Test
    void trailingSlash_isGatedLikeTheBarePath() {
        as("user", "/actuator/env/").exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/env/").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void dotSegmentsEncodingsAndCase_neverReachAnActuatorEndpoint() {
        // The strict firewall refuses most of these (400); a spelling that normalises into the
        // gated subtree answers 403/401; one that is no actuator path for the router either is
        // an unmapped 404. Never 200, so never the endpoint.
        Set<Integer> refused = Set.of(400, 401, 403, 404);
        for (String path : List.of(
                "/actuator/health/../env",
                "/actuator/health/%2e%2e/env",
                "/actuator/health/%2E%2E/env",
                "/actuator/health/..;/env",
                "/actuator/health/%252e%252e/env",
                "/actuator/env;x=y",
                "/actuator;x=y/env",
                "/actuator%2Fenv",
                "/actuator%252Fenv",
                "/actuator/%65nv",
                "/./actuator/env",
                "/ACTUATOR/env")) {
            for (String role : List.of("user", "mod")) {
                var res = as(role, path).exchange().returnResult(String.class);
                assertThat(res.getStatus().value()).as("%s as %s", path, role).isIn(refused);
            }
            var anon = client.get().uri(path).exchange().returnResult(String.class);
            assertThat(anon.getStatus().value()).as("%s anonymous", path).isIn(refused);
        }
    }
}
