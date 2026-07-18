package systems.snapdragon.meshbff.devices;

/**
 * One row of user-bff's {@code GET /bff/user/internal/mesh-devices} response —
 * field names match that JSON contract EXACTLY (verified against
 * {@code InternalMeshDeviceController.BridgeMapping} in
 * snapdragon-user-bff-api's main, and independently against the SAME shape
 * meshbridge's {@code UserBffClient.fetch_mappings()} parses).
 *
 * @param nodeId      canonical {@code "!"+8 lowercase hex} node id.
 * @param localpart   the owner's canonical Matrix localpart (ACTIVE claims only
 *                     — user-bff excludes revoked rows at the SQL layer).
 * @param displayName the name to show for the radio's messages (currently
 *                     always the localpart on the user-bff side).
 * @param visibility   the owner's status-sharing choice: {@code private},
 *                     {@code channels}, or {@code friends}. See
 *                     {@code directory.Visibility} for how this BFF applies it.
 */
public record Registration(String nodeId, String localpart, String displayName, String visibility) {
}
