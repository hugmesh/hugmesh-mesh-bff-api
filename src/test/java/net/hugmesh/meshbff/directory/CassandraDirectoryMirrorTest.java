package net.hugmesh.meshbff.directory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import net.hugmesh.meshbff.cassandra.CassandraSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CassandraDirectoryMirror#loadNodes()}, the fallback read of the
 * node directory, against a MOCKED driver session. No Cassandra is dialled: the mirror
 * builds its session through {@link CassandraSessionFactory#harden}, so a mocked factory
 * hands back a mocked {@link CqlSessionBuilder} whose {@code build()} returns the mocked
 * {@link CqlSession}.
 *
 * <p>What this pins is the wiring around the query, which is what moved when the query
 * body was pulled out of an inline lambda into {@code selectNodes()}: each column still
 * lands in the right {@link MeshNodeRow} field, nothing is dialled or queried until the
 * {@link Flux} is subscribed, the query runs off the calling thread, and a driver
 * failure ends as an empty result (Postgres stays authoritative), never an error.
 *
 * <p>What this does NOT prove: that the CQL itself is accepted by a real Cassandra, or
 * how the real driver turns a NULL column into a Java value. Both need a real cluster.
 */
class CassandraDirectoryMirrorTest {

    private static final Instant SEEN_FIRST = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant SEEN_SECOND = Instant.parse("2026-09-02T08:30:00Z");

    private CassandraSessionFactory factory;
    private CqlSessionBuilder hardened;
    private CqlSession session;
    private CassandraDirectoryMirror mirror;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(CassandraSessionFactory.class);
        hardened = Mockito.mock(CqlSessionBuilder.class);
        session = Mockito.mock(CqlSession.class);
        when(factory.harden(any())).thenReturn(hardened);
        when(hardened.build()).thenReturn(session);
        mirror = new CassandraDirectoryMirror("127.0.0.1:9042", "dc1", "hugmesh_mesh", factory);
    }

    private static Row nodeRow(String nodeId, String longName, String shortName, Instant lastSeenAt,
                               int batteryPct, float snr, int hopsAway, String viaDevice, String domain) {
        Row row = Mockito.mock(Row.class);
        when(row.getString("node_id")).thenReturn(nodeId);
        when(row.getString("long_name")).thenReturn(longName);
        when(row.getString("short_name")).thenReturn(shortName);
        when(row.getInstant("last_seen_at")).thenReturn(lastSeenAt);
        when(row.getInt("battery_pct")).thenReturn(batteryPct);
        when(row.getFloat("snr")).thenReturn(snr);
        when(row.getInt("hops_away")).thenReturn(hopsAway);
        when(row.getString("via_device")).thenReturn(viaDevice);
        when(row.getString("domain")).thenReturn(domain);
        return row;
    }

    private void queryReturns(Row... rows) {
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        when(resultSet.iterator()).thenReturn(List.of(rows).iterator());
        when(session.execute(any(Statement.class))).thenReturn(resultSet);
    }

    @Test
    void loadNodes_mapsEveryColumnOfEveryRow_inResultOrder() {
        queryReturns(
                nodeRow("!a1b2c3d4", "Base Station", "BS", SEEN_FIRST, 87, 6.25f, 2, "wifi0", "default"),
                nodeRow("!0000000f", "Relay", "RL", SEEN_SECOND, 101, -3.5f, 0, "serial1", "brand-b"));

        StepVerifier.create(mirror.loadNodes())
                .expectNext(new MeshNodeRow("!a1b2c3d4", "Base Station", "BS", SEEN_FIRST,
                        87, 6.25f, 2, "wifi0", "default", null))
                .expectNext(new MeshNodeRow("!0000000f", "Relay", "RL", SEEN_SECOND,
                        101, -3.5f, 0, "serial1", "brand-b", null))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Statement<?>> sent = ArgumentCaptor.forClass(Statement.class);
        Mockito.verify(session).execute(sent.capture());
        assertThat(((SimpleStatement) sent.getValue()).getQuery()).contains("FROM mesh_node_directory");
    }

    @Test
    void loadNodes_dialsAndQueriesNothingUntilSubscribed() {
        queryReturns();

        Flux<MeshNodeRow> notYetSubscribed = mirror.loadNodes();

        Mockito.verifyNoInteractions(factory, hardened, session);

        StepVerifier.create(notYetSubscribed).verifyComplete();
        Mockito.verify(session, Mockito.times(1)).execute(any(Statement.class));
    }

    @Test
    void loadNodes_runsTheQueryOffTheCallingThread() {
        AtomicReference<String> queryThread = new AtomicReference<>();
        ResultSet empty = Mockito.mock(ResultSet.class);
        when(empty.iterator()).thenReturn(List.<Row>of().iterator());
        when(session.execute(any(Statement.class))).thenAnswer(call -> {
            queryThread.set(Thread.currentThread().getName());
            return empty;
        });

        StepVerifier.create(mirror.loadNodes()).verifyComplete();

        assertThat(queryThread.get())
                .isNotEqualTo(Thread.currentThread().getName())
                .startsWith("boundedElastic");
    }

    @Test
    void loadNodes_aFailedQueryCompletesEmpty_neverAnError() {
        when(session.execute(any(Statement.class))).thenThrow(new IllegalStateException("cassandra is down"));

        StepVerifier.create(mirror.loadNodes()).verifyComplete();
    }

    @Test
    void loadNodes_aSessionThatCannotBeBuiltCompletesEmpty_neverAnError() {
        when(hardened.build()).thenThrow(new IllegalStateException("no host available"));

        StepVerifier.create(mirror.loadNodes()).verifyComplete();
        Mockito.verifyNoInteractions(session);
    }
}
