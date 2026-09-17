package com.corebank.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The three-letter ISO 4217 shape every currency field in this API takes, currently written out
 * by hand -- identically -- on {@code AmountRequest}, {@code TransferRequest} and
 * {@code OpenAccountRequest}. One definition here instead, so a change to the accepted shape
 * (or its message) can't be applied to one of the three and missed on the others.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
public @interface IsoCurrencyCode {

    String message() default "must be a three-letter ISO 4217 code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
