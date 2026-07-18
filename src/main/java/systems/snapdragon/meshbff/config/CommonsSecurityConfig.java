package systems.snapdragon.meshbff.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import systems.snapdragon.commons.config.TenancyResourceServerConfig;
import systems.snapdragon.commons.security.DomainRegistry;

/**
 * Pulls in the two shared commons beans every consumer of
 * {@code snapdragon-commons} needs wired without widening
 * {@code @SpringBootApplication}'s component scan: {@link DomainRegistry} (issuer
 * -&gt; domain resolution) and {@link TenancyResourceServerConfig} (the OPT-IN
 * multi-issuer resource-server resolver). Mirrors mod-bff-api /
 * snapdragon-admin-bff-api's identical wiring step.
 */
@Configuration
@Import({DomainRegistry.class, TenancyResourceServerConfig.class})
public class CommonsSecurityConfig {
}
