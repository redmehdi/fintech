package com.eurmad.api.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owner-scoped transfer persistence. Every customer read carries a {@code customer_id = ?}
 * predicate, so a foreign or unknown transfer is simply absent. Status changes go only through
 * {@link #transition}, a compare-and-set on status and version; there is no delete.
 */
@Repository
class TransferRepository {

    private static final String COLUMNS =
            "t.id, t.customer_id, t.quote_id, t.beneficiary_id, t.purpose, t.status, t.version, t.created_at,"
                    + " t.updated_at";

    /**
     * Only simulator transfers whose quote and beneficiary belong to the transfer's owner are
     * visible. Rows written before V6 were not checked on insert and may reference another
     * customer's records or a different provider; those stay in the table for traceability but are
     * never returned or transitioned. A beneficiary soft-deleted after the transfer still counts.
     */
    private static final String VISIBLE = " FROM transfers t WHERE t.provider = 'SIMULATOR'"
            + " AND EXISTS (SELECT 1 FROM fx_quotes q WHERE q.id = t.quote_id AND q.customer_id = t.customer_id)"
            + " AND EXISTS (SELECT 1 FROM beneficiaries b WHERE b.id = t.beneficiary_id"
            + " AND b.customer_id = t.customer_id)";

    private final JdbcTemplate jdbc;

    TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertCreated(UUID id, UUID customerId, UUID quoteId, UUID beneficiaryId, String purpose, Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update("INSERT INTO transfers (id, customer_id, quote_id, beneficiary_id, purpose, status, provider,"
                        + " version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'CREATED', 'SIMULATOR', 0, ?, ?)",
                id, customerId, quoteId, beneficiaryId, purpose, at, at);
    }

    /**
     * Moves the transfer from {@code from} (at {@code expectedVersion}) to {@code to}. Returns
     * {@code false} if the row is no longer in that state/version (concurrent change).
     */
    boolean transition(UUID id, TransferStatus from, long expectedVersion, TransferStatus to, Instant now) {
        return jdbc.update("UPDATE transfers SET status = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND status = ? AND version = ?",
                to.name(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC), id, from.name(), expectedVersion) == 1;
    }

    /**
     * Payout country of the caller's active beneficiary, taking a shared row lock held until the
     * transaction ends: a concurrent soft delete waits for this transaction, and one that committed
     * first makes the row invisible here (empty, i.e. {@code 404}).
     */
    Optional<String> lockActiveBeneficiaryCountry(UUID customerId, UUID beneficiaryId) {
        return jdbc.query("SELECT country FROM beneficiaries WHERE id = ? AND customer_id = ? AND deleted_at IS NULL"
                                + " FOR SHARE",
                        (rs, row) -> rs.getString("country"), beneficiaryId, customerId)
                .stream()
                .findFirst();
    }

    Optional<TransferRecord> findOwned(UUID customerId, UUID transferId) {
        return jdbc.query("SELECT " + COLUMNS + VISIBLE + " AND t.id = ? AND t.customer_id = ?",
                        TransferRepository::map, transferId, customerId)
                .stream()
                .findFirst();
    }

    /** Not owner-scoped (system use). */
    Optional<TransferRecord> findById(UUID transferId) {
        return jdbc.query("SELECT " + COLUMNS + VISIBLE + " AND t.id = ?", TransferRepository::map, transferId)
                .stream()
                .findFirst();
    }

    /** Row-locks the transfer until the surrounding transaction ends; not owner-scoped (system use). */
    Optional<TransferRecord> lockById(UUID transferId) {
        return jdbc.query("SELECT " + COLUMNS + VISIBLE + " AND t.id = ? FOR UPDATE OF t",
                        TransferRepository::map, transferId)
                .stream()
                .findFirst();
    }

    List<TransferRecord> listOwned(UUID customerId) {
        return jdbc.query("SELECT " + COLUMNS + VISIBLE + " AND t.customer_id = ?"
                        + " ORDER BY t.created_at DESC, t.id DESC",
                TransferRepository::map, customerId);
    }

    private static TransferRecord map(ResultSet rs, int row) throws SQLException {
        return new TransferRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("quote_id", UUID.class),
                rs.getObject("beneficiary_id", UUID.class),
                rs.getString("purpose"),
                // Unknown values fail closed (the schema allows only the enum's names).
                TransferStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
