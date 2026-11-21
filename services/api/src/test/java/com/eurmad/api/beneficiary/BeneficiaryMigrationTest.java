package com.eurmad.api.beneficiary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
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
 * FX-006: V4 must migrate a database already populated under V3 rules, including rows V4's new
 * constraints would reject. Non-sandbox tokens are quarantined (soft-deleted, replaced by an inert
 * placeholder, original kept only as SHA-256); derivable values are backfilled; IDs never change.
 * All values are synthetic.
 */
@Testcontainers(disabledWithoutDocker = true)
class BeneficiaryMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static UUID insert(JdbcTemplate jdbc, UUID customer, String name, String token, String masked,
            String deletedAtSql) {
        return jdbc.queryForObject("INSERT INTO beneficiaries (customer_id, display_name, country, payout_method,"
                + " destination_token_ref, masked_destination, created_at, deleted_at)"
                + " VALUES (?, ?, 'MA', 'BANK_ACCOUNT', ?, ?, now() - interval '1 day', " + deletedAtSql + ")"
                + " RETURNING id", UUID.class, customer, name, token, masked);
    }

    @Test
    void populatedV3DatabaseMigratesToV4() {
        flyway("3").migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID customer = jdbc.queryForObject("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country)"
                + " VALUES ('https://issuer.invalid', 'test:fx-006:migration', 'ES') RETURNING id", UUID.class);
        String rawIban = "ES9121000418450200051332";

        UUID conforming = insert(jdbc, customer, "Synthetic Kept", "sim-token:ma-bank-mab1", "****mab1", "NULL");
        UUID staleMask = insert(jdbc, customer, "Synthetic Mask", "sim-token:legacy-abcd", "masked", "NULL");
        UUID rawActive = insert(jdbc, customer, "Synthetic Raw", rawIban, "****1332", "NULL");
        UUID rawDeleted = insert(jdbc, customer, "Synthetic Gone", "psp-live:tok_synthetic", "****etic",
                "now() - interval '1 hour'");
        UUID digitRun = insert(jdbc, customer, "Synthetic Digits", "sim-token:123456789x", "****789x", "NULL");
        UUID blankName = insert(jdbc, customer, "   ", "sim-token:ma-bank-mab2", "****mab2", "NULL");
        UUID paddedName = insert(jdbc, customer, "  Synthetic Padded  ", "sim-token:ma-bank-mab3", "****mab3", "NULL");
        UUID longName = insert(jdbc, customer, "n".repeat(130), "sim-token:ma-cash-mac1", "****mac1", "NULL");
        UUID backwards = insert(jdbc, customer, "Synthetic Time", "sim-token:ma-cash-mac2", "****mac2",
                "now() - interval '2 days'");

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM beneficiaries", Integer.class)).isEqualTo(9);
        Map<String, Object> kept = row(jdbc, conforming);
        assertThat(kept).containsEntry("destination_token_ref", "sim-token:ma-bank-mab1")
                .containsEntry("masked_destination", "****mab1").containsEntry("display_name", "Synthetic Kept");
        assertThat(kept.get("deleted_at")).isNull();
        assertThat(row(jdbc, staleMask)).containsEntry("masked_destination", "****abcd");
        assertThat(row(jdbc, staleMask).get("deleted_at")).isNull();

        for (UUID id : new UUID[] {rawActive, rawDeleted, digitRun}) {
            Map<String, Object> quarantined = row(jdbc, id);
            assertThat(quarantined).containsEntry("destination_token_ref", "sim-token:quarantined")
                    .containsEntry("masked_destination", "****ined");
            assertThat(quarantined.get("deleted_at")).as(id.toString()).isNotNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM beneficiary_token_quarantine", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT original_token_sha256 FROM beneficiary_token_quarantine"
                + " WHERE beneficiary_id = ?", String.class, rawActive))
                .isEqualTo(jdbc.queryForObject("SELECT encode(digest(?, 'sha256'), 'hex')", String.class, rawIban));
        // An already deleted row keeps its original deletion time.
        assertThat(jdbc.queryForObject("SELECT deleted_at < now() - interval '30 minutes' FROM beneficiaries"
                + " WHERE id = ?", Boolean.class, rawDeleted)).isTrue();
        String everything = jdbc.queryForObject("SELECT string_agg(b::text, '') FROM beneficiaries b", String.class)
                + jdbc.queryForObject("SELECT string_agg(q::text, '') FROM beneficiary_token_quarantine q",
                        String.class);
        assertThat(everything).doesNotContain(rawIban, "tok_synthetic", "123456789");

        assertThat(row(jdbc, blankName)).containsEntry("display_name", "Unnamed beneficiary");
        assertThat(row(jdbc, paddedName)).containsEntry("display_name", "Synthetic Padded");
        assertThat(row(jdbc, longName)).containsEntry("display_name", "n".repeat(120));
        assertThat(jdbc.queryForObject("SELECT deleted_at = created_at FROM beneficiaries WHERE id = ?",
                Boolean.class, backwards)).isTrue();

        // The constraints are in force for new rows.
        assertThatThrownBy(() -> insert(jdbc, customer, "Synthetic New", rawIban, "****1332", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForMap("SELECT display_name, destination_token_ref, masked_destination, deleted_at"
                + " FROM beneficiaries WHERE id = ?", id);
    }
}
