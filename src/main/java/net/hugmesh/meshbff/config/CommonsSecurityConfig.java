package net.hugmesh.meshbff.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;

/**
 * Pulls in the two shared commons beans every consumer of
 * {@code hugmesh-commons} needs wired without widening
 * {@code @SpringBootApplication}'s component scan: {@link DomainRegistry} (issuer
 * -&gt; domain resolution) and {@link TenancyResourceServerConfig} (the OPT-IN
 * multi-issuer resource-server resolver). Mirrors mod-bff-api /
 * hugmesh-admin-bff-api's identical wiring step.
 */
@Configuration
@Import({DomainRegistry.class, TenancyResourceServerConfig.class})
public class CommonsSecurityConfig {
}
