package systems.snapdragon.meshbff.directory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import systems.snapdragon.meshbff.cassandra.CassandraSessionFactory;

import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The REAL best-effort Cassandra write-through mirror + fallback-read leg,
 * cloned from snapdragon-admin-bff-api's {@code routeaccess.CassandraRouteMirror}
 * shape: lazy double-checked-locking session, every blocking driver call wrapped
 * {@code Mono.fromRunnable/fromSupplier(...).subscribeOn(Schedulers.boundedElastic())
 * .onErrorResume(log + empty)} so a stalled/absent Cassandra never blocks or fails
 * a caller — the Postgres source of truth is always authoritative;
 * this is a read-optimization + a fallback-of-last-resort only.
 *
 * <p>Registers ONLY when {@code app.mirror.cassandra.enabled=true};
 * {@link NoopDirectoryMirror} wins via {@code @ConditionalOnMissingBean}
 * otherwise (see that class's javadoc for the federation-api-derived
 * inversion rationale).
 *
 * <p>Assumes the {@code mesh_node_directory} / {@code mesh_room} Cassandra
 * tables already exist in the configured keyspace (provisioned out of band by
 * the cluster bring-up — same posture as the gateway's
 * {@code gateway_route_access} Cassandra table; this repo owns no Cassandra DDL).
 */
@Component
@ConditionalOnProperty(prefix = "app.mirror.cassandra", name = "enabled", havingValue = "true")
public class CassandraDirectoryMirror implements DirectoryMirror {

    private static final Logger log = LoggerFactory.getLogger(CassandraDirectoryMirror.class);

    private final String contactPoint;
    private final String localDc;
    private final String keyspace;
    private final CassandraSessionFactory sessionFactory;
    private volatile CqlSession session;

    public CassandraDirectoryMirror(
            @Value("${app.mirror.cassandra.contact-point:cassandra.snapdragon-pki.svc.cluster.local:9042}") String contactPoint,
            @Value("${app.mirror.cassandra.local-dc:dc1}") String localDc,
            @Value("${app.mirror.cassandra.keyspace:snapdragon_mesh}") String keyspace,
            CassandraSessionFactory sessionFactory) {
        this.contactPoint = contactPoint;
        this.localDc = localDc;
        this.keyspace = keyspace;
        this.sessionFactory = sessionFactory;
    }

    private CqlSession session() {
        CqlSession s = session;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (session == null) {
                String[] hp = contactPoint.split(":", 2);
                int port = hp.length > 1 ? Integer.parseInt(hp[1].trim()) : 9042;
                session = sessionFactory.harden(CqlSession.builder()
                                .addContactPoint(new InetSocketAddress(hp[0].trim(), port))
                                .withLocalDatacenter(localDc)
                                .withKeyspace(keyspace))
                        .build();
            }
            return session;
        }
    }

    @Override
    public Mono<Void> upsertNode(MeshNodeRow row) {
        return run(() -> session().execute(SimpleStatement.builder(
                                "INSERT INTO mesh_node_directory "
                                        + "(node_id, long_name, short_name, last_seen_at, battery_pct, snr, "
                                        + "hops_away, via_device, domain, updated_at) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, toTimestamp(now()))")
                        .addPositionalValues(row.nodeId(), row.longName(), row.shortName(),
                                row.lastSeenAt(), row.batteryPct(), row.snr(), row.hopsAway(),
                                row.viaDevice(), row.domain())
                        .build()),
                "upsertNode " + row.nodeId());
    }

    @Override
    public Mono<Void> upsertRoom(MeshRoomRow row) {
        return run(() -> session().execute(SimpleStatement.builder(
                                "INSERT INTO mesh_room (room_id, device, mesh_channel, domain, updated_at) "
                                        + "VALUES (?, ?, ?, ?, toTimestamp(now()))")
                        .addPositionalValues(row.roomId(), row.device(), row.meshChannel(), row.domain())
                        .build()),
                "upsertRoom " + row.roomId());
    }

    @Override
    public Flux<MeshNodeRow> loadNodes() {
        return runRead(() -> {
            List<MeshNodeRow> out = new ArrayList<>();
            for (Row r : session().execute(SimpleStatement.builder(
                    "SELECT node_id, long_name, short_name, last_seen_at, battery_pct, snr, "
                            + "hops_away, via_device, domain FROM mesh_node_directory").build())) {
                out.add(new MeshNodeRow(
                        r.getString("node_id"), r.getString("long_name"), r.getString("short_name"),
                        r.getInstant("last_seen_at"), r.getInt("battery_pct"),
                        r.getFloat("snr"), r.getInt("hops_away"), r.getString("via_device"),
                        r.getString("domain"), null));
            }
            return out;
        }, "loadNodes");
    }

    @Override
    public Flux<MeshRoomRow> loadRooms() {
        return runRead(() -> {
            List<MeshRoomRow> out = new ArrayList<>();
            for (Row r : session().execute(SimpleStatement.builder(
                    "SELECT room_id, device, mesh_channel, domain FROM mesh_room").build())) {
                out.add(new MeshRoomRow(r.getString("room_id"), r.getString("device"),
                        r.getInt("mesh_channel"), r.getString("domain"), null));
            }
            return out;
        }, "loadRooms");
    }

    private <T> Flux<T> runRead(Supplier<List<T>> query, String what) {
        return Mono.fromSupplier(query)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .onErrorResume(e -> {
                    log.warn("[mesh-directory-mirror] best-effort {} failed: {} (Postgres remains authoritative)",
                            what, e.toString());
                    return Flux.empty();
                });
    }

    private Mono<Void> run(Runnable op, String what) {
        return Mono.<Void>fromRunnable(op)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("[mesh-directory-mirror] best-effort {} failed: {} (cache may lag Postgres "
                            + "until the next successful refresh)", what, e.toString());
                    return Mono.empty();
                })
                .then();
    }

    @PreDestroy
    void close() {
        CqlSession s = session;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignore) {
                // best-effort shutdown
            }
        }
    }
}
