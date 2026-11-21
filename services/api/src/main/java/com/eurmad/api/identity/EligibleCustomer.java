package com.eurmad.api.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Like {@link CurrentCustomer}, but additionally requires the customer to pass {@link
 * CustomerEligibility} (KYC {@code APPROVED} and status {@code ACTIVE}). Ineligible callers receive
 * 422 before the handler runs. Use on every handler that submits an eligible operation.
 *
 * <p>The status is read fresh from the database for each request. Handlers that change financial
 * workflow state should re-check eligibility inside their own transaction under a customer row
 * lock to avoid acting on a status revoked mid-request.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EligibleCustomer {}
