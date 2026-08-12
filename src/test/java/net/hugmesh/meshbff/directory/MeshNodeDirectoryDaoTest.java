package net.hugmesh.meshbff.directory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.meshbff.support.PostgresSliceTestConfig;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2DBC slice test for the directory repositories + upsert DAO over a
 * Testcontainers-Postgres store that Liquibase applies the REAL production
 * changelog over (the {@link PostgresSliceTestConfig} pattern, cloned from
 * mod-bff-api's {@code ModActionRepositoryTest}). Proves:
 * <ul>
 *   <li>the {@code mesh_node_directory} / {@code mesh_room} DDL + the
 *       {@link MeshNodeDirectoryDao} upsert round-trips on the same schema
 *       that runs in prod;</li>
 *   <li>a repeat upsert of the SAME id UPDATES in place (does not duplicate) —
 *       the exact bug an app-assigned-PK {@code save()} would NOT have caught;</li>
 *   <li>the Postgres-only {@code node_id} regex CHECK constraint rejects a
 *       malformed id at the DB layer (defence-in-depth below the app-layer
 *       {@code BridgeSyncService} normalization).</li>
 * </ul>
 */
@DataR2dbcTest
@ActiveProfiles("test")
// MeshNodeRepository/MeshRoomRepository are NOT imported here (unlike
// PostgresSliceTestConfig/MeshNodeDirectoryDao, which are concrete classes):
// they are interfaces in this SAME package, so @DataR2dbcTest's repository
// auto-configuration already detects + proxies them. @Import on a plain
// interface fails outright ("Specified class is an interface").
@Import({PostgresSliceTestConfig.class, MeshNodeDirectoryDao.class})
class MeshNodeDirectoryDaoTest extends PostgresTestSupport {

    @Autowired MeshNodeRepository nodeRepository;
    @Autowired MeshRoomRepository roomRepository;
    @Autowired MeshNodeDirectoryDao dao;
    @Autowired DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM mesh_node_directory").fetch().rowsUpdated().block();
        db.sql("DELETE FROM mesh_room").fetch().rowsUpdated().block();
    }

    @Test
    void upsertNode_thenFindAll_roundTripsOverRealLiquibaseSchema() {
        Instant seen = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MeshNodeRow row = new MeshNodeRow("!a1b2c3d4", "Base Camp", "BASE", seen, 87, 4.5f,
                2, "wifi0", "default", null);

        StepVerifier.create(dao.upsertNode(row)).verifyComplete();

        StepVerifier.create(nodeRepository.findAll())
                .assertNext(found -> {
                    assertThat(found.nodeId()).isEqualTo("!a1b2c3d4");
                    assertThat(found.longName()).isEqualTo("Base Camp");
                    assertThat(found.shortName()).isEqualTo("BASE");
                    assertThat(found.batteryPct()).isEqualTo(87);
                    assertThat(found.domain()).isEqualTo("default");
                })
                .verifyComplete();
    }

    @Test
    void upsertNode_repeatedForSameId_updatesInPlace_doesNotDuplicate() {
        MeshNodeRow first = new MeshNodeRow("!deadbeef", "First Name", "FST", Instant.now(),
                50, 1.0f, 1, "wifi0", "default", null);
        MeshNodeRow second = new MeshNodeRow("!deadbeef", "Second Name", "SND", Instant.now(),
                99, 2.0f, 3, "wifi1", "default", null);

        dao.upsertNode(first).block();
        dao.upsertNode(second).block();

        StepVerifier.create(nodeRepository.findAll().count())
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        StepVerifier.create(nodeRepository.findById("!deadbeef"))
                .assertNext(found -> {
                    assertThat(found.longName()).isEqualTo("Second Name");
                    assertThat(found.batteryPct()).isEqualTo(99);
                })
                .verifyComplete();
    }

    @Test
    void malformedNodeId_rejectedByDbCheckConstraint() {
        // Bypasses the app-layer BridgeSyncService#normalizeNodeId guard entirely —
        // proves the DB-level CHECK is real defence-in-depth, not just documentation.
        // "!zzzzzzzz" is deliberately the SAME LENGTH/shape as a valid id (fits the
        // varchar(16) column easily) but "z" is not hex — isolates the CHECK
        // constraint specifically, as opposed to a merely-too-long string that would
        // instead trip the column-width limit (a DIFFERENT defence layer; live-verified
        // this is a real, distinct failure mode — a too-long literal fails with
        // Postgres SQLSTATE 22001 "value too long", not the CHECK's 23514).
        StepVerifier.create(db.sql("INSERT INTO mesh_node_directory (node_id, domain) "
                        + "VALUES ('!zzzzzzzz', 'default')")
                        .fetch().rowsUpdated())
                // R2DBC's BadSqlGrammarException.getMessage() is just the generic "bad SQL
                // grammar [<sql>]" wrapper — walk the full cause chain for the driver's
                // actual Postgres error text ("violates check constraint ..."), since the
                // constraint-name detail is not necessarily on the immediate cause.
                .expectErrorMatches(MeshNodeDirectoryDaoTest::mentionsCheckConstraintViolation)
                .verify();
    }

    /** Walk the full cause chain (bounded, to tolerate a self-referential/cyclic
     *  chain) for the Postgres driver's actual error text — "violates check
     *  constraint" and/or the constraint name itself, whichever the driver
     *  surfaces at whatever depth. */
    private static boolean mentionsCheckConstraintViolation(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            String msg = String.valueOf(t.getMessage()).toLowerCase();
            if (msg.contains("check constraint") || msg.contains("ck_mesh_node_directory_id")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void upsertRoom_thenFindAll_roundTrips() {
        MeshRoomRow row = new MeshRoomRow("!room:hugmesh.systems", "wifi0", 3, "default", null);

        StepVerifier.create(dao.upsertRoom(row)).verifyComplete();

        StepVerifier.create(roomRepository.findAll())
                .assertNext(found -> {
                    assertThat(found.roomId()).isEqualTo("!room:hugmesh.systems");
                    assertThat(found.device()).isEqualTo("wifi0");
                    assertThat(found.meshChannel()).isEqualTo(3);
                })
                .verifyComplete();
    }
}
