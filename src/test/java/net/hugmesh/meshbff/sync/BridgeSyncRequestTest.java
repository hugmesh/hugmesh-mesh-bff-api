package net.hugmesh.meshbff.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what {@link BridgeSyncRequest}'s compact constructor does to the two lists
 * it is given: it copies them, it keeps an omitted list as {@code null}, and it
 * drops {@code null} rows instead of throwing on them.
 *
 * <p>The copy is what clears SpotBugs' EI_EXPOSE_REP / EI_EXPOSE_REP2 on this
 * record; the two null rules are what a careless copy would break (a bare
 * {@code List.copyOf} throws on a null list and on a null element).
 */
class BridgeSyncRequestTest {

    private static final BridgeSyncRequest.BridgeNode NODE =
            new BridgeSyncRequest.BridgeNode("!a1b2c3d4", "Long", "S", null, 80, 3.0f, 1, "wifi0");
    private static final BridgeSyncRequest.BridgeNode OTHER_NODE =
            new BridgeSyncRequest.BridgeNode("!0000000f", null, null, null, null, null, null, null);
    private static final BridgeSyncRequest.BridgeRoom ROOM =
            new BridgeSyncRequest.BridgeRoom("!room1:x", "wifi0", 1);

    @Test
    void omittedLists_stayNull_andDoNotThrow() {
        BridgeSyncRequest request = new BridgeSyncRequest(null, null);

        assertThat(request.nodes()).isNull();
        assertThat(request.rooms()).isNull();
    }

    @Test
    void emptyLists_stayEmpty() {
        BridgeSyncRequest request = new BridgeSyncRequest(List.of(), new ArrayList<>());

        assertThat(request.nodes()).isNotNull().isEmpty();
        assertThat(request.rooms()).isNotNull().isEmpty();
    }

    @Test
    void lists_areCopiedOnTheWayIn() {
        List<BridgeSyncRequest.BridgeNode> nodes = new ArrayList<>(List.of(NODE));
        List<BridgeSyncRequest.BridgeRoom> rooms = new ArrayList<>(List.of(ROOM));
        BridgeSyncRequest request = new BridgeSyncRequest(nodes, rooms);

        // The caller keeps its own references and changes both lists afterwards.
        nodes.clear();
        rooms.add(new BridgeSyncRequest.BridgeRoom("!room2:x", "wifi0", 2));

        assertThat(request.nodes()).containsExactly(NODE);
        assertThat(request.rooms()).containsExactly(ROOM);
    }

    @Test
    void accessors_returnListsThatCannotBeChanged() {
        BridgeSyncRequest request = new BridgeSyncRequest(
                new ArrayList<>(List.of(NODE)), new ArrayList<>(List.of(ROOM)));

        assertThatThrownBy(() -> request.nodes().add(OTHER_NODE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.rooms().add(ROOM))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(request.nodes()).containsExactly(NODE);
        assertThat(request.rooms()).containsExactly(ROOM);
    }

    @Test
    void nullRows_areDropped_andTheRestKeepTheirOrder() {
        BridgeSyncRequest request = new BridgeSyncRequest(
                Arrays.asList(null, NODE, null, OTHER_NODE),
                Arrays.asList(ROOM, null));

        assertThat(request.nodes()).containsExactly(NODE, OTHER_NODE);
        assertThat(request.rooms()).containsExactly(ROOM);
    }

    @Test
    void aListOfOnlyNullRows_becomesEmpty_notNull() {
        BridgeSyncRequest request = new BridgeSyncRequest(
                Arrays.asList((BridgeSyncRequest.BridgeNode) null),
                Arrays.asList((BridgeSyncRequest.BridgeRoom) null, null));

        assertThat(request.nodes()).isNotNull().isEmpty();
        assertThat(request.rooms()).isNotNull().isEmpty();
    }
}
