package net.hugmesh.meshbff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * hugmesh-mesh-bff-api — the Meshtastic node-directory / caching layer BFF.
 *
 * <p>Sits alongside (does NOT replace) the optional meshbridge daemon and
 * user-bff's mesh-device registry: it caches a read-optimized node/room
 * directory (Postgres source of truth, best-effort Cassandra mirror, exactly
 * the {@code hugmesh-gateway} {@code GatewayRouteAccessService} caching
 * shape), accepts the bridge's periodic snapshot push, and proxies a caller's
 * OWN registered-device view from user-bff's internal surface.
 *
 * <p>{@code @EnableScheduling} activates the {@code @Scheduled} cache-refresh
 * ticks in {@code directory.MeshDirectoryCache} and {@code devices.MyDevicesCache}.
 */
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
@EnableScheduling
public class MeshBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeshBffApplication.class, args);
    }
}
