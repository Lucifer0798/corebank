package com.corebank.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is acting right now, for recording against what they do.
 *
 * <p>Read from the request's security context rather than passed down through every call. Every
 * posting a person makes arrives as an authenticated request, so the token is already there; and
 * threading an actor parameter through each posting method would touch every caller for no gain
 * in safety -- the parameter could be filled in wrongly just as easily.
 *
 * <p>Jobs with no request behind them declare themselves with {@link #runAs}. That takes precedence
 * over any token, so a job running inside a test that also has a token is still attributed to the
 * job.
 *
 * <p>Anything else is refused rather than recorded as unknown. In production every posting comes
 * from an authenticated request or a declared job, so a posting with neither is a bug -- and an
 * attribution that can quietly read "unknown" is one nobody can rely on.
 */
public final class Actors {

    private static final ThreadLocal<Actor> RUNNING_AS = new ThreadLocal<>();

    /** Lowest precedence, and set only by tests -- see {@link #setTestFallback}. */
    private static final ThreadLocal<Actor> TEST_FALLBACK = new ThreadLocal<>();

    private Actors() {
    }

    /** Runs {@code work} attributed to {@code actor}, for a job no request stands behind. */
    public static void runAs(Actor actor, Runnable work) {
        Actor previous = RUNNING_AS.get();
        RUNNING_AS.set(actor);
        try {
            work.run();
        } finally {
            if (previous == null) {
                RUNNING_AS.remove();
            } else {
                RUNNING_AS.set(previous);
            }
        }
    }

    /**
     * For tests only: who to attribute to when neither a job nor a token says. Never set by
     * production code, where it would turn a missing attribution into a silent one.
     *
     * <p>Lowest precedence on purpose. A default token in the security context was tried first and
     * leaked into MockMvc, turning every request sent without one into an authenticated request --
     * so a test of anonymous access passed through as signed in. Here a request keeps its own token,
     * and one with none stays anonymous.
     */
    public static void setTestFallback(Actor actor) {
        if (actor == null) {
            TEST_FALLBACK.remove();
        } else {
            TEST_FALLBACK.set(actor);
        }
    }

    /** The job running on this thread, else the request's authenticated user. */
    public static Actor current() {
        Actor job = RUNNING_AS.get();
        if (job != null) {
            return job;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return new Actor(token.getToken().getSubject(), token.getToken().getClaimAsString("preferred_username"));
        }
        Actor fallback = TEST_FALLBACK.get();
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("Nothing to attribute this to: no authenticated user, and no job has "
                + "declared itself with Actors.runAs");
    }
}
