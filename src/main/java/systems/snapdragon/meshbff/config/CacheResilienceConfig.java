package systems.snapdragon.meshbff.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Circuit-breaker instance configs for this BFF's two reload loops — cloned
 * verbatim (same tuning) from snapdragon-gateway's
 * {@code config.GatewayResilienceConfig}, which is the sanctioned shape for a
 * {@code ReactiveCircuitBreakerFactory}-guarded cache reload (as opposed to the
 * {@code @CircuitBreaker} AOP-annotation + {@code resilience4j.circuitbreaker
 * .instances.*} YAML mechanism other BFFs use for simple outbound calls — those
 * are TWO DIFFERENT resilience4j integrations; {@link ReactiveResilience4JCircuitBreakerFactory}
 * reads ONLY a {@link Customizer} bean like this one, never the YAML instances
 * block).
 *
 * <ul>
 *   <li>{@code meshDirectory} — {@code directory.MeshDirectoryCache}'s Postgres
 *       (+ Cassandra fallback) reload leg.</li>
 *   <li>{@code meshRegistrations} — {@code devices.RegistrationCache}'s reload
 *       of user-bff's internal mesh-devices mapping.</li>
 * </ul>
 */
@Configuration
public class CacheResilienceConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> meshDirectoryBreakerCustomizer() {
        CircuitBreakerConfig cb = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        TimeLimiterConfig tl = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(3))
                .build();
        return factory -> factory.configure(
                builder -> builder.circuitBreakerConfig(cb).timeLimiterConfig(tl),
                "meshDirectory", "meshRegistrations");
    }
}
