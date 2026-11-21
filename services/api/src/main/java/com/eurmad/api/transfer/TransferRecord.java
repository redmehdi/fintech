package com.eurmad.api.transfer;

import java.time.Instant;
import java.util.UUID;

/** A persisted transfer row as read from {@code transfers}. */
record TransferRecord(
        UUID id,
        UUID customerId,
        UUID quoteId,
        UUID beneficiaryId,
        String purpose,
        TransferStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
