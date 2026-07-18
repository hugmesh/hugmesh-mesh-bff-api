package systems.snapdragon.meshbff.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import systems.snapdragon.commons.config.TenancyResourceServerConfig;
import systems.snapdragon.commons.security.CasdoorAuthorities;
import systems.snapdragon.commons.security.DomainRegistry;

/**
 * Casdoor-aware OAuth2 resource-server security for the mesh BFF (reactive
 * WebFlux). {@code @EnableReactiveMethodSecurity} activates the
 * {@code @PreAuthorize("hasAnyRole('MESH','ADMIN','SUPERADMIN')")} /
 * {@code @PreAuthorize("hasRole('SERVICE')")} gates on the mesh endpoints. The
 * JWT-&gt;authority mapping delegates to the shared {@link CasdoorAuthorities}
 * converter (mirrors mod-bff-api / snapdragon-gateway verbatim): Casdoor's
 * {@code roles} claim (string or {name} objects) -&gt; {@code ROLE_<UPPER>},
 * {@code permissions} verbatim EXCEPT the reserved {@code ROLE_}/{@code DOMAIN_}
 * prefixes (authority-injection defense), legacy {@code isAdmin} -&gt;
 * {@code ROLE_ADMIN}, a {@code ROLE_USER} floor, plus exactly one issuer-derived
 * {@code DOMAIN_<x>} authority (the per-brand tenant scope).
 *
 * <p><b>Two floors, most-specific-first (order matters in
 * {@code authorizeExchange}).</b>
 * <ul>
 *   <li>{@code /bff/mesh/internal/**} — {@code hasRole('SERVICE')} EXCLUSIVELY.
 *       This is the meshbridge daemon's bridge-sync push. The role hierarchy
 *       below has NO edge to SERVICE (not even from SUPERADMIN), mirroring
 *       user-bff-api's {@code InternalMeshDeviceController} /
 *       {@code /bff/user/internal/**} gate exactly — no human JWT may ever
 *       reach this path.</li>
 *   <li>{@code /bff/mesh/**} (everything else under the prefix) —
 *       {@code hasAnyRole('MESH','ADMIN','SUPERADMIN')} at the chain (SUPERADMIN
 *       is the cross-domain infra control plane), with {@code @PreAuthorize}
 *       re-checking at each method (defence in depth).</li>
 * </ul>
 *
 * <p><b>Multi-issuer (per-brand Casdoor) is OPT-IN + backward-compatible.</b> A
 * single-issuer cluster keeps the EXISTING single {@code o.jwt(...)} issuer-uri
 * decoder path verbatim. Only when {@link TenancyResourceServerConfig}
 * contributes a per-issuer resolver AND {@link DomainRegistry#isMultiIssuer()}
 * does the chain swap to the per-issuer authentication-manager resolver (each
 * trusted brand Casdoor validates its own tokens; an unknown {@code iss} 401s,
 * fail-closed).
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * SpringDoc OpenAPI spec + Swagger UI surface. Public under dev/test so
     * {@code gen:fetch} can curl the spec without a token; ROLE_ADMIN (or
     * SUPERADMIN) everywhere else (prod) so the API contract is never publicly
     * reachable.
     */
    private static final String[] SPEC_PATHS = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/swagger-ui/**"
    };

    private final DomainRegistry domainRegistry;

    /** OPTIONAL per-brand multi-issuer resolver, contributed by
     *  {@link TenancyResourceServerConfig} ONLY when {@code app.tenancy.issuers}
     *  genuinely carries issuer=domain pairs. Absent on a single-issuer cluster. */
    private final ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
            tenantAuthManagerResolver;

    /** D8 MFA floor (fan-out from the gateway). Ships DARK — {@code app.mfa.enabled}
     *  defaults {@code false}, so the converter emits the byte-identical legacy
     *  authority set and {@code MFA_SATISFIED} is never produced. */
    private final boolean mfaEnabled;
    private final long mfaFreshnessWindowMillis;

    public SecurityConfig(DomainRegistry domainRegistry,
                          ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
                                  tenantAuthManagerResolver,
                          @Value("${app.mfa.enabled:false}") boolean mfaEnabled,
                          @Value("${app.mfa.freshness-window:600000}") long mfaFreshnessWindowMillis) {
        this.domainRegistry = domainRegistry;
        this.tenantAuthManagerResolver = tenantAuthManagerResolver;
        this.mfaEnabled = mfaEnabled;
        this.mfaFreshnessWindowMillis = mfaFreshnessWindowMillis;
    }

    /**
     * Shared route rules common to BOTH the dev/test and prod filter chains. The
     * ONLY difference between the two profiles is how {@link #SPEC_PATHS} is
     * gated, so every other route lives here to keep the two chains in lockstep.
     */
    private static void commonRoutes(ServerHttpSecurity.AuthorizeExchangeSpec ex) {
        // Probes are public so k8s/Docker healthchecks need no token.
        ex.pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
        // SERVICE-PRINCIPAL ONLY — the meshbridge daemon's bridge-sync push. MUST be
        // declared BEFORE the broader /bff/mesh/** matcher below (first match wins).
        // No ROLE_ADMIN/ROLE_SUPERADMIN superset here — the role hierarchy has no
        // edge to SERVICE (see roleHierarchy()), same posture as user-bff-api's
        // /bff/user/internal/** gate.
        ex.pathMatchers("/bff/mesh/internal/**").hasRole("SERVICE");
        // Mesh surface — defence-in-depth on top of the gateway's route-access rules
        // + the @PreAuthorize method gates. A MESH holder, PLUS the cross-domain
        // ADMIN/SUPERADMIN infra roles (which reach every brand's surface).
        ex.pathMatchers("/bff/mesh/**").hasAnyRole("MESH", "ADMIN", "SUPERADMIN");
    }

    /**
     * Hardened HTTP response headers for a pure-JSON resource server (the BFF
     * defensive-minimum from the org security plan: OWASP A05 / ASVS V14.4 /
     * NIST 800-228). This BFF NEVER serves an HTML document and always sits
     * BEHIND the TLS-terminating gateway front door — mirrors mod-bff-api
     * verbatim.
     */
    private static void hardenedHeaders(ServerHttpSecurity.HeaderSpec h) {
        h.contentSecurityPolicy(csp ->
                csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"));
        // nosniff + Cache-Control + X-Frame-Options DENY + Referrer-Policy remain Spring
        // Security reactive secure defaults — intentionally left implicit (NOT disabled).
    }

    /**
     * Dev / test profile: the OpenAPI spec + Swagger UI are PUBLIC so
     * {@code gen:fetch} (ng-openapi-gen) and the docs pipeline can curl
     * {@code /v3/api-docs} locally without a Casdoor token. Every non-spec route
     * is identical to prod.
     */
    @Bean
    @Profile({"dev", "test"})
    SecurityWebFilterChain devFilterChain(final ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(SecurityConfig::hardenedHeaders)
                .authorizeExchange(ex -> {
                    commonRoutes(ex);
                    ex.pathMatchers(SPEC_PATHS).permitAll();
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(this::configureResourceServer)
                .build();
    }

    /**
     * Production profile (default — active whenever neither {@code dev} nor
     * {@code test} is): the OpenAPI spec + Swagger UI require ROLE_ADMIN (or the
     * cross-domain SUPERADMIN) so the API contract is never publicly reachable.
     * Every non-spec route is identical to dev/test.
     */
    @Bean
    @Profile("!dev & !test")
    SecurityWebFilterChain filterChain(final ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(SecurityConfig::hardenedHeaders)
                .authorizeExchange(ex -> {
                    commonRoutes(ex);
                    ex.pathMatchers(SPEC_PATHS).hasAnyRole("ADMIN", "SUPERADMIN");
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(this::configureResourceServer)
                .build();
    }

    /**
     * Wire the resource server. Multi-issuer is OPT-IN: swap to the per-issuer
     * authentication-manager resolver ONLY when BOTH a resolver was contributed
     * by {@link TenancyResourceServerConfig} AND the registry is genuinely
     * multi-issuer (more than the lone back-compat entry).
     */
    private void configureResourceServer(ServerHttpSecurity.OAuth2ResourceServerSpec o) {
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                tenantAuthManagerResolver.getIfAvailable();
        if (resolver != null && domainRegistry.isMultiIssuer()) {
            o.authenticationManagerResolver(resolver);
        } else {
            o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter()));
        }
    }

    /**
     * Method-security role hierarchy. {@code ROLE_SUPERADMIN} is a strict
     * superset of admin/mesh/user; {@code ROLE_ADMIN} and {@code ROLE_MESH} stay
     * documented SIBLINGS (no {@code ADMIN > MESH} edge — a plain ADMIN never
     * silently becomes a mesh-access holder for the VISIBILITY rules, only for
     * the coarse route gate above via the explicit {@code hasAnyRole} pairing).
     * Deliberately NO edge to {@code ROLE_SERVICE} — mirrors user-bff-api's
     * internal-surface posture: even SUPERADMIN must never reach
     * {@code /bff/mesh/internal/**}.
     */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(
                "ROLE_SUPERADMIN > ROLE_ADMIN\n" +
                "ROLE_SUPERADMIN > ROLE_MESH\n" +
                "ROLE_SUPERADMIN > ROLE_USER");
    }

    /**
     * Wire {@link #roleHierarchy()} into REACTIVE method security so the
     * {@code @PreAuthorize("hasAnyRole('MESH','ADMIN','SUPERADMIN')")} gates
     * honour {@code ROLE_SUPERADMIN > ROLE_ADMIN/ROLE_MESH}. A bare
     * {@link RoleHierarchy} bean is NOT auto-applied to {@code @PreAuthorize} in
     * the reactive method-security path, so the expression handler is set
     * explicitly (same pattern as mod-bff-api / forum-bff / wiki-bff).
     */
    @Bean
    MethodSecurityExpressionHandler meshMethodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        // Keep the default ROLE_ prefix explicit so hasRole('ADMIN') maps to ROLE_ADMIN.
        handler.setDefaultRolePrefix(new GrantedAuthorityDefaults("ROLE_").getRolePrefix());
        return handler;
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Casdoor JWT -&gt; authorities conversion. Delegates to the shared
     * {@link CasdoorAuthorities} so the single-issuer path here and the optional
     * multi-issuer path ({@link TenancyResourceServerConfig}) stay
     * byte-identical.
     */
    Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return CasdoorAuthorities.converter(domainRegistry,
                new CasdoorAuthorities.MfaConfig(mfaEnabled, mfaFreshnessWindowMillis));
    }
}
