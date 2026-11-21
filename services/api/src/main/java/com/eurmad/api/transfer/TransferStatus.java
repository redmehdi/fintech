package com.eurmad.api.transfer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transfer workflow states and the only permitted transitions (docs/ARCHITECTURE.md §5). The same
 * graph is enforced by the database function {@code transfer_transition_allowed} (V6). Statuses are
 * workflow facts, never balances: no state implies the application holds or moves funds.
 */
public enum TransferStatus {
    CREATED,
    AWAITING_KYC,
    AWAITING_FUNDING,
    FUNDING_CONFIRMED,
    PROCESSING,
    REVIEW_REQUIRED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
    REFUND_PENDING,
    REFUNDED;

    private static final Map<TransferStatus, Set<TransferStatus>> NEXT = new EnumMap<>(TransferStatus.class);

    static {
        for (TransferStatus status : values()) {
            NEXT.put(status, EnumSet.noneOf(TransferStatus.class));
        }
        allow(CREATED, AWAITING_KYC, AWAITING_FUNDING);
        allow(AWAITING_KYC, AWAITING_FUNDING, REJECTED);
        allow(AWAITING_FUNDING, FUNDING_CONFIRMED, CANCELLED);
        allow(FUNDING_CONFIRMED, PROCESSING, REFUND_PENDING);
        allow(PROCESSING, COMPLETED, REVIEW_REQUIRED, FAILED, REFUND_PENDING);
        allow(REVIEW_REQUIRED, PROCESSING, FAILED);
        // FAILED is terminal unless funds were received, in which case a refund is due.
        allow(FAILED, REFUND_PENDING);
        allow(REFUND_PENDING, REFUNDED);
    }

    private static void allow(TransferStatus from, TransferStatus... to) {
        NEXT.get(from).addAll(EnumSet.of(to[0], to));
    }

    /** States reachable in one step; empty for {@code COMPLETED}, {@code REJECTED}, {@code CANCELLED}, {@code REFUNDED}. */
    public Set<TransferStatus> next() {
        return Collections.unmodifiableSet(NEXT.get(this));
    }

    public boolean canTransitionTo(TransferStatus target) {
        return target != null && NEXT.get(this).contains(target);
    }
}
