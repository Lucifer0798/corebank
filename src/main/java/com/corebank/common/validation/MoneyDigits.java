package com.corebank.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Digits;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The precision every {@code BigDecimal} money field in this API is stored at -- {@code
 * NUMERIC(19,4)} columns hold more, but 15 integer digits and 2 decimal places is the shape every
 * request DTO validates against. Deliberately just the digit-shape constraint, not a minimum: a
 * required, strictly-positive amount ({@code AmountRequest}/{@code TransferRequest}) and an
 * optional, zero-or-more one ({@code OpenAccountRequest}'s overdraft limit) need different
 * {@code @DecimalMin}s, so that stays on the field, not bundled in here.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = {})
@Digits(integer = 15, fraction = 2, message = "supports at most two decimal places")
public @interface MoneyDigits {

    String message() default "supports at most two decimal places";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
