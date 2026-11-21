package com.eurmad.api.identity;

import com.eurmad.api.error.ApiProblemException;

/**
 * Eligibility gate for operations that change financial workflow state (quotes, transfers, offers,
 * match acceptance). Fails closed: only an {@code ACTIVE} customer whose KYC status is exactly
 * {@code APPROVED} is eligible; every other or unknown value is rejected with 422.
 *
 * <p>All operations remain simulation-only; eligibility never enables live money movement.
 */
public final class CustomerEligibility {

    static final String APPROVED_KYC = "APPROVED";
    static final String ACTIVE_STATUS = "ACTIVE";

    private CustomerEligibility() {}

    public static boolean isEligible(CustomerProfile profile) {
        return profile != null
                && APPROVED_KYC.equals(profile.kycStatus())
                && ACTIVE_STATUS.equals(profile.status());
    }

    public static void requireEligible(CustomerProfile profile) {
        if (!isEligible(profile)) {
            throw ApiProblemException.unprocessable();
        }
    }
}
