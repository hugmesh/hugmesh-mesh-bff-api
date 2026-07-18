package systems.snapdragon.meshbff.directory;

/**
 * VISIBILITY RULES v1 — deliberate, honest, and documented VERBATIM (do not
 * silently "improve" this without updating both this class and the README):
 *
 * <ul>
 *   <li><b>Anonymous</b> (no active registration for the node id in user-bff) —
 *       ALWAYS listed, WITHOUT attribution. The raw radio telemetry is mesh
 *       infrastructure data; there is no owner to protect.</li>
 *   <li><b>{@code private}</b> — attribution AND status are shown ONLY to the
 *       owner (the caller's JWT {@code localpart} claim equals the
 *       registration's {@code localpart}). Every other caller sees the row as
 *       a bare node id (no telemetry, no attribution) — see
 *       {@code directory.MeshNodeView#of}.</li>
 *   <li><b>{@code channels}</b> — visible (attribution + status) to ANY
 *       ROLE_MESH caller in the SAME domain as the node's directory row (this
 *       BFF's own {@code mesh_node_directory.domain} column). This is a v1
 *       APPROXIMATION of "any caller who shares a mesh-enabled room/channel
 *       with the owner" — precise room-membership-based visibility is NOT
 *       implemented (queued; would need a room-membership query this BFF does
 *       not yet have a source for).</li>
 *   <li><b>{@code friends}</b> — v1 = {@code private} (owner only). The
 *       platform has NO friendship/follow primitive to query, and one is
 *       deliberately NOT invented here — queued alongside precise
 *       {@code channels} room-membership.</li>
 *   <li>An unrecognized/malformed visibility value fails CLOSED (treated as
 *       {@code private}).</li>
 * </ul>
 */
public final class Visibility {

    public static final String PRIVATE = "private";
    public static final String CHANNELS = "channels";
    public static final String FRIENDS = "friends";

    private Visibility() {
    }

    /**
     * Whether attribution + live status may be shown to this caller for a
     * REGISTERED node. Unregistered (anonymous) nodes never call this — they
     * are unconditionally shown without attribution (see class doc).
     *
     * <p>The {@code isSuperadmin} bypass below is NOT one of the four v1 rules
     * documented above (those cover {@code private}/{@code channels}/
     * {@code friends}/anonymous only) — it is an explicit, deliberate ADDITION
     * for consistency with every other cross-tenant/visibility predicate on the
     * platform ({@code security.Roles#domainApplies}, the gateway's
     * {@code decide()}, etc.), all of which give {@code ROLE_SUPERADMIN} an
     * unconditional bypass. Called out here explicitly rather than folded
     * silently into the "four rules" so a reviewer sees it is a platform-wide
     * convention, not a fifth invented visibility tier.
     */
    public static boolean permits(String visibility, boolean isOwner, boolean sameDomain, boolean isSuperadmin) {
        if (isSuperadmin || isOwner) {
            return true;
        }
        String v = visibility == null ? PRIVATE : visibility;
        return switch (v) {
            case CHANNELS -> sameDomain;
            // FRIENDS v1 == PRIVATE (no friendship primitive — do not invent one here).
            case PRIVATE, FRIENDS -> false;
            default -> false;
        };
    }
}
