package com.eurmad.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * FX-008: V6 must migrate a database populated under V5 rules, including a transfer row written
 * before any orchestration existed. The legacy row is retained but bound by the state machine and
 * cannot be deleted. All values are synthetic.
 */
@Testcontainers(disabledWithoutDocker = true)
class TransferMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Each test starts from an empty database (the container is shared by the class). */
    @BeforeEach
    void emptyDatabase() {
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())).execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void populatedV5DatabaseMigratesToV6() {
        flyway("5").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID customer = jdbc.queryForObject("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country)"
                + " VALUES ('https://issuer.invalid', 'test:fx-008:migration', 'ES') RETURNING id", UUID.class);
        UUID beneficiary = jdbc.queryForObject("INSERT INTO beneficiaries (customer_id, display_name, country,"
                + " payout_method, destination_token_ref, masked_destination) VALUES (?, 'Synthetic', 'MA',"
                + " 'BANK_ACCOUNT', 'sim-token:ma-bank-mab1', '****mab1') RETURNING id", UUID.class, customer);
        UUID quote = jdbc.queryForObject("INSERT INTO fx_quotes (customer_id, source_currency, target_currency,"
                + " source_amount_minor, fee_source_minor, fee_fixed_minor, total_debit_minor, target_amount_minor,"
                + " rate, rate_source, expires_at) VALUES (?, 'EUR', 'MAD', 100, 0, 0, 100, 1070, 10.7,"
                + " 'SIMULATED_ILLUSTRATIVE', now() + interval '10 minutes') RETURNING id", UUID.class, customer);
        UUID legacy = jdbc.queryForObject("INSERT INTO transfers (customer_id, quote_id, beneficiary_id, purpose,"
                + " status, provider) VALUES (?, ?, ?, 'FAMILY_SUPPORT', 'PROCESSING', 'LEGACY') RETURNING id",
                UUID.class, customer, quote, beneficiary);

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, legacy))
                .isEqualTo("PROCESSING");
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status = 'CREATED', version = version + 1"
                + " WHERE id = ?", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfers WHERE id = ?", legacy))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO transaction_events (resource_kind, resource_id, next_status, actor_type, actor_ref,"
                + " correlation_id) VALUES ('TRANSFER', ?, 'PROCESSING', 'SYSTEM', 'migration-test', ?)",
                legacy, UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transaction_events WHERE resource_id = ?", legacy))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Pre-V6 rows were never checked on insert. After V6, reads and system transitions expose only
     * simulator transfers whose quote and beneficiary belong to the transfer's owner; a valid row
     * whose beneficiary was later soft-deleted stays in the owner's history.
     */
    @Test
    void legacyRowsWithForeignReferencesOrProviderAreNotExposed() {
        flyway("5").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID owner = customer(jdbc, "owner");
        UUID other = customer(jdbc, "other");
        UUID ownBeneficiary = beneficiary(jdbc, owner);
        UUID otherBeneficiary = beneficiary(jdbc, other);

        UUID valid = legacyTransfer(jdbc, owner, quote(jdbc, owner), ownBeneficiary, "SIMULATOR");
        UUID foreignQuote = legacyTransfer(jdbc, owner, quote(jdbc, other), ownBeneficiary, "SIMULATOR");
        UUID foreignBeneficiary = legacyTransfer(jdbc, owner, quote(jdbc, owner), otherBeneficiary, "SIMULATOR");
        UUID foreignProvider = legacyTransfer(jdbc, owner, quote(jdbc, owner), ownBeneficiary, "LEGACY_PSP");
        jdbc.update("UPDATE beneficiaries SET deleted_at = now() WHERE id = ?", ownBeneficiary);

        flyway("latest").migrate();

        TransferRepository repository = new TransferRepository(jdbc);
        assertThat(repository.listOwned(owner)).extracting(TransferRecord::id).containsExactly(valid);
        assertThat(repository.findOwned(owner, valid)).isPresent();
        for (UUID hidden : List.of(foreignQuote, foreignBeneficiary, foreignProvider)) {
            assertThat(repository.findOwned(owner, hidden)).as(hidden.toString()).isEmpty();
            assertThat(repository.findById(hidden)).as(hidden.toString()).isEmpty();
            assertThat(repository.lockById(hidden)).as(hidden.toString()).isEmpty();
        }
        assertThat(repository.listOwned(other)).isEmpty();
        // The rows themselves are retained for traceability.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE customer_id = ?", Integer.class, owner))
                .isEqualTo(4);
    }

    private static UUID customer(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country)"
                + " VALUES ('https://issuer.invalid', ?, 'ES') RETURNING id", UUID.class,
                "test:fx-008:legacy-" + name + "-" + UUID.randomUUID());
    }

    private static UUID beneficiary(JdbcTemplate jdbc, UUID customer) {
        return jdbc.queryForObject("INSERT INTO beneficiaries (customer_id, display_name, country, payout_method,"
                + " destination_token_ref, masked_destination) VALUES (?, 'Synthetic', 'MA', 'BANK_ACCOUNT',"
                + " 'sim-token:ma-bank-mab1', '****mab1') RETURNING id", UUID.class, customer);
    }

    private static UUID quote(JdbcTemplate jdbc, UUID customer) {
        return jdbc.queryForObject("INSERT INTO fx_quotes (customer_id, source_currency, target_currency,"
                + " source_amount_minor, fee_source_minor, fee_fixed_minor, total_debit_minor, target_amount_minor,"
                + " rate, rate_source, expires_at) VALUES (?, 'EUR', 'MAD', 100, 0, 0, 100, 1070, 10.7,"
                + " 'SIMULATED_ILLUSTRATIVE', now() + interval '10 minutes') RETURNING id", UUID.class, customer);
    }

    private static UUID legacyTransfer(JdbcTemplate jdbc, UUID customer, UUID quote, UUID beneficiary,
            String provider) {
        return jdbc.queryForObject("INSERT INTO transfers (customer_id, quote_id, beneficiary_id, purpose, status,"
                + " provider) VALUES (?, ?, ?, 'FAMILY_SUPPORT', 'AWAITING_FUNDING', ?) RETURNING id",
                UUID.class, customer, quote, beneficiary, provider);
    }
}
