package com.eurmad.api.beneficiary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owner-scoped beneficiary persistence. Every query carries a {@code customer_id = ?} predicate
 * and excludes soft-deleted rows, so a foreign, deleted or unknown beneficiary is simply absent.
 */
@Repository
public class BeneficiaryRepository {

    private static final String COLUMNS = "id, display_name, country, payout_method, masked_destination";

    private final JdbcTemplate jdbc;

    BeneficiaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Beneficiary> listActive(UUID customerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM beneficiaries"
                        + " WHERE customer_id = ? AND deleted_at IS NULL ORDER BY created_at, id",
                BeneficiaryRepository::map, customerId);
    }

    /**
     * The caller's beneficiary if it exists and is not deleted. Later operations (e.g. transfer
     * creation) must resolve beneficiaries only through this method.
     */
    public Optional<Beneficiary> findActiveOwned(UUID customerId, UUID beneficiaryId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM beneficiaries"
                        + " WHERE id = ? AND customer_id = ? AND deleted_at IS NULL",
                BeneficiaryRepository::map, beneficiaryId, customerId)
                .stream()
                .findFirst();
    }

    /** Includes deleted rows; used only to answer idempotent create replays. */
    Optional<Boolean> isDeleted(UUID customerId, UUID beneficiaryId) {
        return jdbc.query("SELECT deleted_at IS NOT NULL FROM beneficiaries WHERE id = ? AND customer_id = ?",
                        (rs, row) -> rs.getBoolean(1), beneficiaryId, customerId)
                .stream()
                .findFirst();
    }

    void insert(UUID id, UUID customerId, BeneficiaryRules.ValidCreate request, String maskedDestination) {
        jdbc.update("INSERT INTO beneficiaries (id, customer_id, display_name, country, payout_method,"
                        + " destination_token_ref, masked_destination) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, customerId, request.displayName(), request.country(), request.payoutMethod(),
                request.destinationTokenRef(), maskedDestination);
    }

    /** Soft-deletes an active owned beneficiary and returns it; empty if absent, foreign or deleted. */
    Optional<Beneficiary> softDelete(UUID customerId, UUID beneficiaryId) {
        return jdbc.query("UPDATE beneficiaries SET deleted_at = now()"
                        + " WHERE id = ? AND customer_id = ? AND deleted_at IS NULL"
                        + " RETURNING " + COLUMNS,
                        BeneficiaryRepository::map, beneficiaryId, customerId)
                .stream()
                .findFirst();
    }

    private static Beneficiary map(ResultSet rs, int row) throws SQLException {
        return new Beneficiary(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getString("country"),
                rs.getString("payout_method"),
                rs.getString("masked_destination"));
    }
}
