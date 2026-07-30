package systems.hugmesh.meshbff.directory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default {@link DirectoryMirror} when Cassandra is disabled (the common dev /
 * CI / test case, and every cluster that has not opted into the mirror): every
 * write is a no-op and every read is empty, so {@link MeshDirectoryCache}
 * always renders from Postgres and simply keeps its last-good in-memory
 * snapshot if a Postgres reload ever fails (never a Cassandra-shaped error).
 *
 * <p>Cloned from hugmesh-federation-api's {@code cache.NoopManifestCache}
 * bean-selection shape: registered via {@code @ConditionalOnMissingBean} so
 * {@link CassandraDirectoryMirror} wins whenever
 * {@code app.mirror.cassandra.enabled=true} — cleaner than double-gating this
 * bean, since exactly one of the two conditions is ever true.
 */
@Configuration
public class NoopDirectoryMirror {

    @Bean
    @ConditionalOnMissingBean(DirectoryMirror.class)
    DirectoryMirror noopDirectoryMirrorBean() {
        return new DirectoryMirror() {
            @Override
            public Mono<Void> upsertNode(MeshNodeRow row) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> upsertRoom(MeshRoomRow row) {
                return Mono.empty();
            }

            @Override
            public Flux<MeshNodeRow> loadNodes() {
                return Flux.empty();
            }

            @Override
            public Flux<MeshRoomRow> loadRooms() {
                return Flux.empty();
            }
        };
    }
}
