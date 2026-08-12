package net.hugmesh.meshbff.sync;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import net.hugmesh.meshbff.directory.MeshNodeDirectoryDao;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/** Pure unit tests for the tolerant/clamped bridge-sync ingest normalization
 *  ({@link BridgeSyncService}), with a mocked {@link MeshNodeDirectoryDao} (no DB). */
class BridgeSyncServiceTest {

    // ── nodeId normalization ──────────────────────────────────────────────

    @Test
    void normalizeNodeId_acceptsCanonicalForm() {
        assertThat(BridgeSyncService.normalizeNodeId("!a1b2c3d4")).isEqualTo("!a1b2c3d4");
    }

    @Test
    void normalizeNodeId_toleratesMissingBang() {
        assertThat(BridgeSyncService.normalizeNodeId("a1b2c3d4")).isEqualTo("!a1b2c3d4");
    }

    @Test
    void normalizeNodeId_toleratesUppercaseHex() {
        assertThat(BridgeSyncService.normalizeNodeId("!A1B2C3D4")).isEqualTo("!a1b2c3d4");
    }

    @Test
    void normalizeNodeId_rejectsWrongLength() {
        assertThat(BridgeSyncService.normalizeNodeId("!a1b2c3")).isNull();
    }

    @Test
    void normalizeNodeId_rejectsNonHex() {
        assertThat(BridgeSyncService.normalizeNodeId("!zzzzzzzz")).isNull();
    }

    @Test
    void normalizeNodeId_rejectsNull() {
        assertThat(BridgeSyncService.normalizeNodeId(null)).isNull();
    }

    // ── battery clamping ──────────────────────────────────────────────────

    @Test
    void clampBattery_passesThroughInRange() {
        assertThat(BridgeSyncService.clampBattery(50)).isEqualTo(50);
    }

    @Test
    void clampBattery_clampsAboveMax() {
        assertThat(BridgeSyncService.clampBattery(500)).isEqualTo(101);
    }

    @Test
    void clampBattery_clampsBelowMin() {
        assertThat(BridgeSyncService.clampBattery(-30)).isEqualTo(0);
    }

    @Test
    void clampBattery_nullPassesThrough() {
        assertThat(BridgeSyncService.clampBattery(null)).isNull();
    }

    // ── end-to-end apply(): malformed rows are skipped, never fail the request ──

    @Test
    void apply_skipsMalformedRows_countsOnlyValidUpserts() {
        MeshNodeDirectoryDao dao = Mockito.mock(MeshNodeDirectoryDao.class);
        Mockito.when(dao.upsertNode(any())).thenReturn(Mono.empty());
        Mockito.when(dao.upsertRoom(any())).thenReturn(Mono.empty());
        BridgeSyncService service = new BridgeSyncService(dao);

        BridgeSyncRequest request = new BridgeSyncRequest(
                List.of(
                        new BridgeSyncRequest.BridgeNode("!a1b2c3d4", "Good", "G", Instant.now(), 80, 3.0f, 1, "wifi0"),
                        new BridgeSyncRequest.BridgeNode("not-hex-at-all", "Bad", "B", Instant.now(), 10, 1.0f, 1, "wifi0")),
                List.of(
                        new BridgeSyncRequest.BridgeRoom("!room1:x", "wifi0", 1),
                        new BridgeSyncRequest.BridgeRoom("", "wifi0", 2)));

        StepVerifier.create(service.apply(request))
                .assertNext(result -> {
                    assertThat(result.nodesUpserted()).isEqualTo(1);
                    assertThat(result.roomsUpserted()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void apply_nullListsTreatedAsEmpty() {
        MeshNodeDirectoryDao dao = Mockito.mock(MeshNodeDirectoryDao.class);
        BridgeSyncService service = new BridgeSyncService(dao);

        StepVerifier.create(service.apply(new BridgeSyncRequest(null, null)))
                .assertNext(result -> {
                    assertThat(result.nodesUpserted()).isEqualTo(0);
                    assertThat(result.roomsUpserted()).isEqualTo(0);
                })
                .verifyComplete();
        Mockito.verifyNoInteractions(dao);
    }
}
