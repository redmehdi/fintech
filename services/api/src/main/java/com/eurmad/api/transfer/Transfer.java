package com.eurmad.api.transfer;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Contract {@code Transfer} representation. Excludes the owner ID and provider references. */
public record Transfer(
        UUID id,
        UUID quoteId,
        UUID beneficiaryId,
        String purpose,
        String status,
        String mode,
        String createdAt,
        String updatedAt) {

    static final String MODE = "SIMULATION";

    static Transfer from(TransferRecord transfer) {
        return new Transfer(
                transfer.id(),
                transfer.quoteId(),
                transfer.beneficiaryId(),
                transfer.purpose(),
                transfer.status().name(),
                MODE,
                DateTimeFormatter.ISO_INSTANT.format(transfer.createdAt()),
                DateTimeFormatter.ISO_INSTANT.format(transfer.updatedAt()));
    }
}
