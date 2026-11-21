package com.eurmad.api.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.kyc.VerificationDecision.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class KycSandboxUnitTest {

    private static final UUID CASE_ID = UUID.fromString("00000000-0000-4000-8000-00000000f005");

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"APPROVED", "REJECTED"})
    void sandboxIsDeterministicForConfiguredOutcome(Outcome outcome) {
        SandboxIdentityVerificationProvider provider =
                new SandboxIdentityVerificationProvider(new KycSandboxProperties(outcome));

        VerificationDecision first = provider.startVerification(CASE_ID);
        VerificationDecision second = provider.startVerification(CASE_ID);

        assertThat(first).isEqualTo(second);
        assertThat(first.outcome()).isEqualTo(outcome);
        assertThat(first.providerCaseRef()).isEqualTo("sandbox-kyc:" + CASE_ID);
        assertThat(first.reasonCode())
                .isEqualTo(outcome == Outcome.REJECTED ? "SANDBOX_SIMULATED_REJECTION" : null);
        assertThat(provider.name()).isEqualTo("SANDBOX");
    }

    @Test
    void pendingCannotBeConfiguredBecauseNothingWouldResolveIt() {
        assertThatThrownBy(() -> new KycSandboxProperties(Outcome.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyNotStartedOrExpiredMayStartVerification() {
        assertThat(KycStatus.NOT_STARTED.canStartVerification()).isTrue();
        assertThat(KycStatus.EXPIRED.canStartVerification()).isTrue();
        assertThat(KycStatus.PENDING.canStartVerification()).isFalse();
        assertThat(KycStatus.APPROVED.canStartVerification()).isFalse();
        assertThat(KycStatus.REJECTED.canStartVerification()).isFalse();
    }

    @Test
    void unknownStoredStatusDoesNotParse() {
        assertThat(KycStatus.parse("approved")).isEmpty();
        assertThat(KycStatus.parse(null)).isEmpty();
        assertThat(KycStatus.parse("APPROVED")).contains(KycStatus.APPROVED);
    }

    @Test
    void decisionRejectsFreeTextReasonsAndBlankReferences() {
        assertThatThrownBy(() -> new VerificationDecision("ref", Outcome.REJECTED, "passport 123 invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerificationDecision(" ", Outcome.APPROVED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerificationDecision("ref", null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
