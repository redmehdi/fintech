package com.eurmad.api.quote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owner-scoped quote persistence. Every read carries a {@code customer_id = ?} predicate, so a
 * foreign or unknown quote is simply absent. There is no update or delete method: quotes are
 * immutable (also enforced by the {@code fx_quotes_immutable} trigger).
 */
@Repository
class QuoteRepository {

    private static final String COLUMNS = "id, customer_id, source_currency, target_currency, source_amount_minor,"
            + " fee_source_minor, fee_fixed_minor, total_debit_minor, target_amount_minor, rate, rate_source,"
            + " created_at, expires_at, status";

    private final JdbcTemplate jdbc;

    QuoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(QuoteRecord quote) {
        jdbc.update("INSERT INTO fx_quotes (id, customer_id, quote_type, source_currency, target_currency,"
                        + " source_amount_minor, fee_source_minor, fee_fixed_minor, total_debit_minor,"
                        + " target_amount_minor, rate, rate_source, mode, status, created_at, expires_at)"
                        + " VALUES (?, ?, 'TRANSFER', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SIMULATION', ?, ?, ?)",
                quote.id(), quote.customerId(), quote.sourceCurrency().name(), quote.targetCurrency().name(),
                quote.sourceAmountMinor(), quote.feeSourceMinor(), quote.feeFixedMinor(), quote.totalDebitMinor(),
                quote.targetAmountMinor(), quote.rate(), quote.rateSource(), quote.status(),
                OffsetDateTime.ofInstant(quote.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(quote.expiresAt(), ZoneOffset.UTC));
    }

    Optional<QuoteRecord> findOwned(UUID customerId, UUID quoteId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM fx_quotes WHERE id = ? AND customer_id = ?",
                        QuoteRepository::map, quoteId, customerId)
                .stream()
                .findFirst();
    }

    /** Like {@link #findOwned} but row-locks the quote until the surrounding transaction ends. */
    Optional<QuoteRecord> lockOwned(UUID customerId, UUID quoteId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM fx_quotes WHERE id = ? AND customer_id = ? FOR UPDATE",
                        QuoteRepository::map, quoteId, customerId)
                .stream()
                .findFirst();
    }

    /**
     * ISSUED -> CONSUMED for an owned quote; returns the number of rows changed (0 or 1). The
     * {@code fx_quotes_immutable} trigger rejects the change once the quote's expiry has passed.
     */
    int markConsumed(UUID customerId, UUID quoteId) {
        return jdbc.update("UPDATE fx_quotes SET status = 'CONSUMED' WHERE id = ? AND customer_id = ? AND status = 'ISSUED'",
                quoteId, customerId);
    }

    private static QuoteRecord map(ResultSet rs, int row) throws SQLException {
        return new QuoteRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                // Unknown codes fail closed (the schema allows only EUR and MAD).
                CurrencyCode.valueOf(rs.getString("source_currency")),
                CurrencyCode.valueOf(rs.getString("target_currency")),
                rs.getLong("source_amount_minor"),
                rs.getLong("fee_source_minor"),
                rs.getLong("fee_fixed_minor"),
                rs.getLong("total_debit_minor"),
                rs.getLong("target_amount_minor"),
                rs.getBigDecimal("rate"),
                rs.getString("rate_source"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getString("status"));
    }
}
