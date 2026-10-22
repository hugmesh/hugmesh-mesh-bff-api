package net.hugmesh.meshbff.db;

import liquibase.integration.spring.SpringLiquibase;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the changeSet author rename in {@code db.changelog-master.xml} is safe on
 * a database that already applied 001, 001b and 002 under their former author.
 *
 * <p>Liquibase identifies an applied changeSet by id + author + filename, so after
 * the rename those three look new on such a database. Without the
 * {@code onFail="MARK_RAN"} preconditions Liquibase would try to create
 * {@code mesh_node_directory} again and the service would not start. The former
 * author value is simulated here with a placeholder: the preconditions look at the
 * schema, not at the old author, so any value exercises the same path.
 *
 * <p>Runs the PRODUCTION changelog through {@link SpringLiquibase}, the same runner
 * the service uses, against a fresh database on the shared Testcontainers Postgres.
 */
class ChangelogAuthorRenameTest extends PostgresTestSupport {

    private static final String CHANGELOG = "classpath:db/changelog/db.changelog-master.xml";
    private static final String AUTHOR = "hugmesh";
    private static final String LEGACY_AUTHOR = "legacy-author";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() throws Exception {
        String dbName = "authorrename_" + SEQ.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + dbName);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    private void runChangelog() throws Exception {
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(dataSource);
        lb.setChangeLog(CHANGELOG);
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
    }

    private List<Map<String, Object>> changelogRows(String author) {
        return jdbc.queryForList(
                "SELECT id, exectype FROM databasechangelog WHERE author = ? ORDER BY id", author);
    }

    private int checkConstraintCount() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_mesh_node_directory_id'",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void freshDatabase_runsEveryChangeSetForReal() throws Exception {
        runChangelog();

        assertThat(changelogRows(AUTHOR))
                .extracting(r -> r.get("id") + ":" + r.get("exectype"))
                .containsExactly(
                        "001-mesh-node-directory:EXECUTED",
                        "001b-mesh-node-directory-id-check:EXECUTED",
                        "002-mesh-room:EXECUTED");
        assertThat(checkConstraintCount()).isEqualTo(1);
    }

    @Test
    void databaseMigratedUnderFormerAuthor_upgradesWithoutRecreatingAnything() throws Exception {
        // A database migrated before the rename: same changeSets, same schema, but
        // every DATABASECHANGELOG row carries the former author.
        runChangelog();
        jdbc.update("UPDATE databasechangelog SET author = ?", LEGACY_AUTHOR);
        jdbc.update("INSERT INTO mesh_room (room_id, device, mesh_channel) VALUES ('!room:example', 'dev0', 1)");

        // The upgrade run: without the MARK_RAN preconditions this throws
        // "relation \"mesh_node_directory\" already exists".
        runChangelog();

        assertThat(changelogRows(AUTHOR))
                .extracting(r -> r.get("id") + ":" + r.get("exectype"))
                .containsExactly(
                        "001-mesh-node-directory:MARK_RAN",
                        "001b-mesh-node-directory-id-check:MARK_RAN",
                        "002-mesh-room:MARK_RAN");
        assertThat(changelogRows(LEGACY_AUTHOR)).hasSize(3);
        assertThat(checkConstraintCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mesh_room", Integer.class)).isEqualTo(1);

        // And a later run is a no-op: nothing new is recorded.
        runChangelog();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class))
                .isEqualTo(6);
    }
}
