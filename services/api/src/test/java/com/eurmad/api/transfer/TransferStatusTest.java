package com.eurmad.api.transfer;

import static com.eurmad.api.transfer.TransferStatus.AWAITING_FUNDING;
import static com.eurmad.api.transfer.TransferStatus.AWAITING_KYC;
import static com.eurmad.api.transfer.TransferStatus.CANCELLED;
import static com.eurmad.api.transfer.TransferStatus.COMPLETED;
import static com.eurmad.api.transfer.TransferStatus.CREATED;
import static com.eurmad.api.transfer.TransferStatus.FAILED;
import static com.eurmad.api.transfer.TransferStatus.FUNDING_CONFIRMED;
import static com.eurmad.api.transfer.TransferStatus.PROCESSING;
import static com.eurmad.api.transfer.TransferStatus.REFUNDED;
import static com.eurmad.api.transfer.TransferStatus.REFUND_PENDING;
import static com.eurmad.api.transfer.TransferStatus.REJECTED;
import static com.eurmad.api.transfer.TransferStatus.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** FX-008: the transfer state machine matches docs/ARCHITECTURE.md §5 exactly. */
class TransferStatusTest {

    private static final Map<TransferStatus, Set<TransferStatus>> EXPECTED = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(AWAITING_KYC, AWAITING_FUNDING)),
            Map.entry(AWAITING_KYC, EnumSet.of(AWAITING_FUNDING, REJECTED)),
            Map.entry(AWAITING_FUNDING, EnumSet.of(FUNDING_CONFIRMED, CANCELLED)),
            Map.entry(FUNDING_CONFIRMED, EnumSet.of(PROCESSING, REFUND_PENDING)),
            Map.entry(PROCESSING, EnumSet.of(COMPLETED, REVIEW_REQUIRED, FAILED, REFUND_PENDING)),
            Map.entry(REVIEW_REQUIRED, EnumSet.of(PROCESSING, FAILED)),
            Map.entry(FAILED, EnumSet.of(REFUND_PENDING)),
            Map.entry(REFUND_PENDING, EnumSet.of(REFUNDED)),
            Map.entry(COMPLETED, EnumSet.noneOf(TransferStatus.class)),
            Map.entry(REJECTED, EnumSet.noneOf(TransferStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(TransferStatus.class)),
            Map.entry(REFUNDED, EnumSet.noneOf(TransferStatus.class)));

    @Test
    void everyPairFollowsTheDocumentedGraph() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(TransferStatus.values());
        for (TransferStatus from : TransferStatus.values()) {
            assertThat(from.next()).as(from.name()).isEqualTo(EXPECTED.get(from));
            for (TransferStatus to : TransferStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + "->" + to).isEqualTo(EXPECTED.get(from).contains(to));
            }
            assertThat(from.canTransitionTo(from)).as("no self transition " + from).isFalse();
            assertThat(from.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    void completionRequiresProcessingAndCannotBeSkippedTo() {
        for (TransferStatus from : TransferStatus.values()) {
            assertThat(from.canTransitionTo(COMPLETED)).as(from.name()).isEqualTo(from == PROCESSING);
        }
        assertThat(CREATED.canTransitionTo(FUNDING_CONFIRMED)).isFalse();
        assertThat(AWAITING_FUNDING.canTransitionTo(PROCESSING)).isFalse();
    }

    @Test
    void nextIsUnmodifiable() {
        assertThatThrownBy(() -> CREATED.next().add(COMPLETED)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(CREATED.canTransitionTo(COMPLETED)).isFalse();
    }
}
