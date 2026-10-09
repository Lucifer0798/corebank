package com.corebank.common.security;

import java.util.Objects;

/**
 * Who did something: a member of staff from their token, or a named system job.
 *
 * @param subject the token's {@code sub} claim -- stable for the life of the Keycloak user -- or
 *                {@code system:<job>} for a job
 * @param name    something a person recognises: the username at the time, or the job's name
 */
public record Actor(String subject, String name) {

    public Actor {
        Objects.requireNonNull(subject, "an actor needs a subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("an actor needs a subject");
        }
    }

    public static Actor system(String job) {
        return new Actor("system:" + job, job);
    }
}
