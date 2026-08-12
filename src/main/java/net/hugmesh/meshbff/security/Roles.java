package net.hugmesh.meshbff.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Per-repo domain-scoping helpers that sit ON TOP of
 * {@link net.hugmesh.commons.security.Roles} (the JWT/authority
 * primitives — {@code domainOf(Jwt, DomainRegistry)},
 * {@code domainOf(Collection)}, {@code isSuperadmin(...)}). Mirrors
 * mod-bff-api's / hugmesh-gateway's local {@code security.Roles}: this class
 * carries ONLY the {@link Authentication}-typed overloads (a WebFlux controller
 * method's {@code Authentication} parameter, not a raw {@code Jwt}) plus the
 * {@link #domainApplies} cross-tenant predicate, which is specific to how THIS
 * BFF's {@code mesh_node_directory} / {@code mesh_room} rows are scoped — none
 * of that lives in commons.
 */
public final class Roles {

    private Roles() {
    }

    /**
     * Resolve the caller's domain from an already-authenticated
     * {@link Authentication} (delegates to the commons authorities-based
     * overload — fail-closed: zero or more-than-one {@code DOMAIN_} authority
     * yields {@code null}, an injection signature never picks "the first").
     */
    public static String domainOf(Authentication auth) {
        if (auth == null) {
            return null;
        }
        return net.hugmesh.commons.security.Roles.domainOf(authorities(auth));
    }

    /** Whether the caller holds the cross-domain {@code ROLE_SUPERADMIN} authority. */
    public static boolean isSuperadmin(Authentication auth) {
        if (auth == null) {
            return false;
        }
        return net.hugmesh.commons.security.Roles.isSuperadmin(authorities(auth));
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends GrantedAuthority> authorities(Authentication auth) {
        return (Collection<? extends GrantedAuthority>) auth.getAuthorities();
    }

    /**
     * The cross-tenant visibility predicate every mesh directory read applies:
     * does a row scoped to {@code rowDomain} apply to a caller scoped to
     * {@code callerDomain}?
     *
     * <ul>
     *   <li>a SUPERADMIN caller sees every row, regardless of domain;</li>
     *   <li>{@code null}/blank/{@code "default"} {@code rowDomain} is GLOBAL —
     *       applies to any caller (back-compat: a single-tenant cluster's rows,
     *       and every canonical anonymous/unregistered node, carry no domain);</li>
     *   <li>otherwise an exact, case-insensitive match against
     *       {@code callerDomain} — a {@code null} {@code callerDomain} never
     *       matches a scoped row (deny; mirrors mod-bff-api's identical
     *       fail-closed rule).</li>
     * </ul>
     */
    public static boolean domainApplies(String rowDomain, String callerDomain, boolean isSuperadmin) {
        if (isSuperadmin) {
            return true;
        }
        if (isGlobal(rowDomain)) {
            return true;
        }
        if (callerDomain == null || callerDomain.isBlank()) {
            return false;
        }
        return rowDomain.equalsIgnoreCase(callerDomain);
    }

    private static boolean isGlobal(String domain) {
        return domain == null || domain.isBlank() || "default".equalsIgnoreCase(domain);
    }
}
