package com.eurmad.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.quote.CurrencyCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TransferRulesTest {

    private static final String QUOTE = "3f1c2a4e-5b6d-4e7f-8a9b-0c1d2e3f4a5b";
    private static final String BENEFICIARY = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d";

    private static void assertBadRequest(String quoteId, String beneficiaryId, String purpose) {
        assertThatThrownBy(() -> TransferRules.validateCreate(quoteId, beneficiaryId, purpose))
                .as(quoteId + "/" + beneficiaryId + "/" + purpose)
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void acceptsCanonicalIdsAndKnownPurposes() {
        for (String purpose : TransferRules.PURPOSES) {
            TransferRules.ValidCreate valid = TransferRules.validateCreate(QUOTE, BENEFICIARY, purpose);
            assertThat(valid.quoteId()).isEqualTo(UUID.fromString(QUOTE));
            assertThat(valid.beneficiaryId()).isEqualTo(UUID.fromString(BENEFICIARY));
            assertThat(valid.purpose()).isEqualTo(purpose);
        }
        // Case is not significant for UUIDs, so both spellings are the same request.
        assertThat(TransferRules.validateCreate(QUOTE.toUpperCase(), BENEFICIARY, "EDUCATION"))
                .isEqualTo(TransferRules.validateCreate(QUOTE, BENEFICIARY, "EDUCATION"));
    }

    @Test
    void rejectsMalformedFields() {
        assertBadRequest(null, BENEFICIARY, "EDUCATION");
        assertBadRequest(QUOTE, null, "EDUCATION");
        assertBadRequest(QUOTE, BENEFICIARY, null);
        assertBadRequest("1-1-1-1-1", BENEFICIARY, "EDUCATION");
        assertBadRequest(" " + QUOTE, BENEFICIARY, "EDUCATION");
        assertBadRequest(QUOTE + "0", BENEFICIARY, "EDUCATION");
        assertBadRequest(QUOTE, "not-a-uuid", "EDUCATION");
        assertBadRequest(QUOTE, BENEFICIARY, "education");
        assertBadRequest(QUOTE, BENEFICIARY, "GIFT");
    }

    @Test
    void idempotencyKeyIsPrintableAsciiOfBoundedLength() {
        assertThat(TransferRules.requireIdempotencyKey("abcdefgh")).isEqualTo("abcdefgh");
        assertThat(TransferRules.requireIdempotencyKey("x".repeat(128))).hasSize(128);
        for (String bad : new String[] {null, "short", "x".repeat(129), "has space1", "tab\tkey12", "ключ-ключ"}) {
            assertThatThrownBy(() -> TransferRules.requireIdempotencyKey(bad)).as(String.valueOf(bad))
                    .isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void payoutCountryFollowsTheTargetCurrency() {
        assertThat(TransferRules.payoutCountry(CurrencyCode.MAD)).contains("MA");
        assertThat(TransferRules.payoutCountry(CurrencyCode.EUR)).contains("ES");
    }
}
