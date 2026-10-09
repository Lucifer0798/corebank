package com.corebank.config;

import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Gives every Spring test someone to attribute its postings to, so the deposits tests make directly
 * through the services need no token -- see {@code Actors}. Production refuses a posting with nobody
 * behind it.
 *
 * <p>Uses Actors' test fallback, not the security context: a default token there leaked into
 * MockMvc and made requests sent without one authenticated. The fallback is consulted last, so a
 * job or a request's own token still wins.
 *
 * <p>Registered for every test in {@code META-INF/spring.factories}. Set before each test method,
 * which runs ahead of {@code @BeforeEach}, and cleared after.
 */
public class TestActorListener implements TestExecutionListener {

    public static final Actor STAFF = new Actor("test-staff-subject", "test-staff");

    @Override
    public void beforeTestMethod(TestContext testContext) {
        Actors.setTestFallback(STAFF);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        Actors.setTestFallback(null);
    }
}
