package systems.snapdragon.meshbff.directory;

/** The public {@code GET /bff/mesh/rooms} shape — domain-scoped, no visibility gating
 *  (rooms carry no per-row owner/visibility concept, unlike nodes). */
public record MeshRoomView(String roomId, String device, Integer meshChannel) {

    public static MeshRoomView of(MeshRoomRow row) {
        return new MeshRoomView(row.roomId(), row.device(), row.meshChannel());
    }
}
