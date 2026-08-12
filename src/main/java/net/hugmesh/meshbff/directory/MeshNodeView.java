package net.hugmesh.meshbff.directory;

import net.hugmesh.meshbff.devices.Registration;

/**
 * The public {@code GET /bff/mesh/nodes} / {@code GET /bff/mesh/status/{nodeId}}
 * shape — {@link MeshNodeRow} PLUS the derived {@code online} flag and the
 * visibility-gated {@code registeredTo} attribution. See {@link Visibility}
 * for the v1 rules this factory applies.
 */
public record MeshNodeView(
        String nodeId,
        String longName,
        String shortName,
        java.time.Instant lastSeenAt,
        Integer batteryPct,
        Float snr,
        Integer hopsAway,
        String viaDevice,
        boolean online,
        String registeredTo) {

    /**
     * Build the caller-facing view of one directory row, applying
     * {@link Visibility}. {@code registration} is {@code null} for an
     * anonymous (unregistered) node — always shown WITHOUT attribution but
     * WITH full telemetry. For a registered node whose visibility does not
     * permit this caller, the row is reduced to a bare node id: every
     * telemetry field nulled, {@code online=false}, {@code registeredTo=null}
     * — "attribution+status only to owner" (see {@link Visibility}).
     */
    public static MeshNodeView of(MeshNodeRow row, boolean online, Registration registration,
                                  String callerLocalpart, String callerDomain, boolean isSuperadmin) {
        if (registration == null) {
            // Anonymous — always listed, full telemetry, never attributed.
            return new MeshNodeView(row.nodeId(), row.longName(), row.shortName(), row.lastSeenAt(),
                    row.batteryPct(), row.snr(), row.hopsAway(), row.viaDevice(), online, null);
        }
        boolean isOwner = callerLocalpart != null && callerLocalpart.equals(registration.localpart());
        boolean sameDomain = row.domain() == null || row.domain().isBlank() || "default".equals(row.domain())
                || row.domain().equalsIgnoreCase(callerDomain);
        boolean permitted = Visibility.permits(registration.visibility(), isOwner, sameDomain, isSuperadmin);
        if (!permitted) {
            return new MeshNodeView(row.nodeId(), null, null, null, null, null, null, null, false, null);
        }
        return new MeshNodeView(row.nodeId(), row.longName(), row.shortName(), row.lastSeenAt(),
                row.batteryPct(), row.snr(), row.hopsAway(), row.viaDevice(), online, registration.localpart());
    }
}
