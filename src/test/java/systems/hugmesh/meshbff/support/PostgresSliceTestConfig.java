package systems.hugmesh.meshbff.support;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Runs the PRODUCTION Liquibase changelog over the Testcontainers-Postgres
 * database that {@link systems.hugmesh.commons.testsupport.PostgresTestSupport}
 * provisions, for the {@code @DataR2dbcTest} directory-repository SLICES.
 * Cloned from mod-bff-api's {@code support.PostgresSliceTestConfig}.
 *
 * <p>WHY a dedicated config (vs. the full-context boot tests): a
 * {@code @DataR2dbcTest} slice auto-configures ONLY the reactive R2DBC stack —
 * it does NOT load Spring Boot's Liquibase auto-configuration — and the
 * {@code test} profile disables Liquibase outright
 * ({@code spring.liquibase.enabled=false}). This {@code @TestConfiguration}
 * re-adds a {@link SpringLiquibase} bean (Liquibase has no R2DBC runner, so it
 * uses a JDBC datasource) pointed at the SAME container db that
 * {@code PostgresTestSupport.@DynamicPropertySource} published into
 * {@code spring.liquibase.*} — so the slice runs its R2DBC queries against the
 * REAL schema (including the Postgres-only {@code node_id} regex CHECK
 * constraint, which H2 cannot honour).
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresSliceTestConfig {

    @Bean
    public DataSource liquibaseDataSource(
            @Value("${spring.liquibase.url}") String url,
            @Value("${spring.liquibase.user}") String user,
            @Value("${spring.liquibase.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public SpringLiquibase liquibase(DataSource liquibaseDataSource) {
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(liquibaseDataSource);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return lb;
    }
}
