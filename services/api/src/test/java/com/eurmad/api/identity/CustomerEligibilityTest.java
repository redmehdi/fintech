package com.eurmad.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.error.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CustomerEligibilityTest {

    private static CustomerProfile profile(String kycStatus, String status) {
        return new CustomerProfile(UUID.randomUUID(), "ES", kycStatus, status);
    }

    @Test
    void approvedActiveCustomerIsEligible() {
        assertThat(CustomerEligibility.isEligible(profile("APPROVED", "ACTIVE"))).isTrue();
        CustomerEligibility.requireEligible(profile("APPROVED", "ACTIVE"));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "NOT_STARTED, ACTIVE",
        "PENDING, ACTIVE",
        "REJECTED, ACTIVE",
        "EXPIRED, ACTIVE",
        "approved, ACTIVE",
        "NULL, ACTIVE",
        "APPROVED, SUSPENDED",
        "APPROVED, CLOSED",
        "APPROVED, NULL",
    })
    void everythingElseFailsClosedWith422(String kycStatus, String status) {
        assertThat(CustomerEligibility.isEligible(profile(kycStatus, status))).isFalse();
        assertThatThrownBy(() -> CustomerEligibility.requireEligible(profile(kycStatus, status)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.status().value()).isEqualTo(422));
    }

    @Test
    void missingProfileIsIneligible() {
        assertThat(CustomerEligibility.isEligible(null)).isFalse();
    }
}
