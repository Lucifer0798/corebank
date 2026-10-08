package com.corebank.config;

import com.corebank.customer.domain.KycDecider;

/** Who makes the KYC decisions tests need as setup. Named, so a test's decisions are recognisable. */
public final class TestDeciders {

    public static final KycDecider STAFF = new KycDecider("test-staff-subject", "test-staff");

    private TestDeciders() {
    }
}
