package net.hugmesh.meshbff.sync;

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
 * The bridge-sync push over real HTTP, through the real JSON decoder, for the three
 * body shapes a careless defensive copy in {@link BridgeSyncRequest} would reject:
 * a list left out, a list sent as {@code null}, and a list holding {@code null} rows.
 *
 * <p>Same full-context boot as {@code SecurityMatrixTest} ({@code test} profile, a
 * mocked {@link ReactiveJwtDecoder} standing in for Casdoor), so Spring reuses that
 * cached context. Every row in these bodies is dropped before the service reaches
 * the datastore, which is why the profile's empty H2 store is enough here; the
 * "valid rows beside a null row are still written" half is pinned against a mocked
 * DAO in {@code BridgeSyncServiceTest}.
 *
 * <p>All three must answer 200. The endpoint's contract is that a malformed row is
 * skipped, never a failure of the whole push.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class BridgeSyncWireTest {

    private static final String TOKEN = "tok-bridge-sync-wire";

    @Autowired
    WebTestClient client;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    private WebTestClient.ResponseSpec pushAsService(String json) {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "meshbridge")
                .claim("roles", List.of(Map.of("name", "service")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(eq(TOKEN))).thenReturn(Mono.just(jwt));
        return client.post().uri("/bff/mesh/internal/bridge-sync")
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange();
    }

    private static void expectAppliedWithNothingUpserted(WebTestClient.ResponseSpec response) {
        response.expectStatus().isOk()
                .expectBody()
                .jsonPath("$.nodesUpserted").isEqualTo(0)
                .jsonPath("$.roomsUpserted").isEqualTo(0);
    }

    @Test
    void omittedLists_areAccepted() {
        expectAppliedWithNothingUpserted(pushAsService("{}"));
    }

    @Test
    void explicitNullLists_areAccepted() {
        expectAppliedWithNothingUpserted(pushAsService("{\"nodes\":null,\"rooms\":null}"));
    }

    @Test
    void nullRows_areSkipped_notAFailedRequest() {
        expectAppliedWithNothingUpserted(pushAsService("{\"nodes\":[null,null],\"rooms\":[null]}"));
    }
}
