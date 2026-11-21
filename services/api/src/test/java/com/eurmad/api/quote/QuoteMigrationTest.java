package com.eurmad.api.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * FX-007: V5 must migrate a database populated under V4 rules, including quote rows written before
 * any pricing module existed (non-simulated rate source, long expiry). Legacy issued quotes are
 * retired so they can never be consumed, and every legacy row is frozen. All values are synthetic.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuoteMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static UUID insert(JdbcTemplate jdbc, UUID customer, String rateSource, String status) {
        return jdbc.queryForObject("INSERT INTO fx_quotes (customer_id, source_currency, target_currency,"
                + " source_amount_minor, fee_source_minor, total_debit_minor, target_amount_minor, rate, rate_source,"
                + " status, expires_at) VALUES (?, 'EUR', 'MAD', 100, 0, 100, 1070, 10.7, ?, ?, now() + interval"
                + " '2 days') RETURNING id", UUID.class, customer, rateSource, status);
    }

    @Test
    void populatedV4DatabaseMigratesToV5() {
        flyway("4").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID customer = jdbc.queryForObject("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country)"
                + " VALUES ('https://issuer.invalid', 'test:fx-007:migration', 'ES') RETURNING id", UUID.class);
        UUID issued = insert(jdbc, customer, "legacy-manual", "ISSUED");
        UUID consumed = insert(jdbc, customer, "legacy-manual", "CONSUMED");

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT status FROM fx_quotes WHERE id = ?", String.class, issued))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM fx_quotes WHERE id = ?", String.class, consumed))
                .isEqualTo("CONSUMED");
        // Legacy rows are retained for traceability but frozen.
        assertThatThrownBy(() -> jdbc.update("UPDATE fx_quotes SET status = 'ISSUED' WHERE id = ?", issued))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fx_quotes WHERE id = ?", consumed))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The new checks apply to every new row.
        assertThatThrownBy(() -> insert(jdbc, customer, "legacy-manual", "ISSUED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
