package systems.snapdragon.meshbff.directory;

import org.junit.jupiter.api.Test;
import systems.snapdragon.meshbff.devices.Registration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link MeshNodeView#of} — the visibility-gated projection every /bff/mesh/nodes
 *  row + /bff/mesh/status/{id} response goes through. */
class MeshNodeViewTest {

    private static final MeshNodeRow ROW = new MeshNodeRow(
            "!a1b2c3d4", "Long Name", "SHRT", Instant.parse("2026-07-18T00:00:00Z"),
            80, 3.2f, 2, "wifi0", "default", null);

    @Test
    void anonymousNode_alwaysShownWithFullTelemetry_neverAttributed() {
        MeshNodeView view = MeshNodeView.of(ROW, true, null, "alice", "default", false);
        assertThat(view.nodeId()).isEqualTo("!a1b2c3d4");
        assertThat(view.longName()).isEqualTo("Long Name");
        assertThat(view.batteryPct()).isEqualTo(80);
        assertThat(view.online()).isTrue();
        assertThat(view.registeredTo()).isNull();
    }

    @Test
    void registeredNode_ownerCaller_seesFullAttributionAndStatus() {
        Registration reg = new Registration("!a1b2c3d4", "alice", "alice", Visibility.PRIVATE);
        MeshNodeView view = MeshNodeView.of(ROW, true, reg, "alice", "default", false);
        assertThat(view.registeredTo()).isEqualTo("alice");
        assertThat(view.batteryPct()).isEqualTo(80);
        assertThat(view.online()).isTrue();
    }

    @Test
    void registeredNode_privateVisibility_nonOwnerCaller_reducedToBareNodeId() {
        Registration reg = new Registration("!a1b2c3d4", "alice", "alice", Visibility.PRIVATE);
        MeshNodeView view = MeshNodeView.of(ROW, true, reg, "bob", "default", false);
        assertThat(view.nodeId()).isEqualTo("!a1b2c3d4");
        assertThat(view.registeredTo()).isNull();
        assertThat(view.longName()).isNull();
        assertThat(view.batteryPct()).isNull();
        assertThat(view.online()).isFalse();
    }

    @Test
    void registeredNode_channelsVisibility_sameDomainCaller_seesFullAttributionAndStatus() {
        Registration reg = new Registration("!a1b2c3d4", "alice", "alice", Visibility.CHANNELS);
        MeshNodeView view = MeshNodeView.of(ROW, true, reg, "bob", "default", false);
        assertThat(view.registeredTo()).isEqualTo("alice");
        assertThat(view.batteryPct()).isEqualTo(80);
    }

    @Test
    void registeredNode_channelsVisibility_differentDomainCaller_reducedToBareNodeId() {
        // A row explicitly scoped to "brand-x" (NOT the "default"/global sentinel —
        // ROW above carries domain="default", which is intentionally global/unscoped
        // and visible to any caller regardless of domain, per Roles#domainApplies'
        // convention) is NOT visible cross-domain under `channels`.
        MeshNodeRow scopedRow = new MeshNodeRow(ROW.nodeId(), ROW.longName(), ROW.shortName(),
                ROW.lastSeenAt(), ROW.batteryPct(), ROW.snr(), ROW.hopsAway(), ROW.viaDevice(),
                "brand-x", null);
        Registration reg = new Registration("!a1b2c3d4", "alice", "alice", Visibility.CHANNELS);
        MeshNodeView view = MeshNodeView.of(scopedRow, true, reg, "bob", "other-brand", false);
        assertThat(view.registeredTo()).isNull();
        assertThat(view.batteryPct()).isNull();
    }

    @Test
    void registeredNode_superadminCaller_alwaysSeesFullAttributionAndStatus() {
        Registration reg = new Registration("!a1b2c3d4", "alice", "alice", Visibility.PRIVATE);
        MeshNodeView view = MeshNodeView.of(ROW, true, reg, "bob", "other-brand", true);
        assertThat(view.registeredTo()).isEqualTo("alice");
        assertThat(view.batteryPct()).isEqualTo(80);
    }
}
