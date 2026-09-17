package com.corebank.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A required, strictly-positive money amount -- the shape every deposit, withdrawal and transfer
 * amount takes, currently written out by hand -- identically -- on {@code AmountRequest} and
 * {@code TransferRequest}. One definition here instead, so a change to the minimum or the digit
 * shape can't be applied to one and missed on the other. See {@link MoneyDigits} for why the
 * digit-shape half of this is its own separate, reusable constraint rather than folded in here.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = {})
@NotNull
@DecimalMin(value = "0.01", message = "must be at least 0.01")
@MoneyDigits
public @interface PositiveAmount {

    String message() default "must be a positive amount with at most two decimal places";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
