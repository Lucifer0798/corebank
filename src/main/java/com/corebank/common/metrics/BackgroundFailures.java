package com.corebank.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * One counter for every background task's failures, tagged by task, so one alert covers them all.
 *
 * <p>The runners catch a failure so it cannot stop the rest of their batch, which is right, but it
 * left a failure as a WARN line and nothing else. An interest run failing on the same account every
 * hour, or a hold that never expires, showed up nowhere anyone was watching.
 *
 * <p>Each runner registers its counter in its constructor, at zero. A counter made on first use is
 * born at 1, and Prometheus's {@code increase()} needs a sample before the change to see one, so
 * the first failure would never fire the alert.
 *
 * <p>The tag is {@code task}, not {@code job}: Prometheus puts its own {@code job} label on every
 * series it scrapes and would rename ours to {@code exported_job}.
 */
public final class BackgroundFailures {

    public static final String METRIC = "corebank.background.failures";

    private BackgroundFailures() {
    }

    public static Counter counter(MeterRegistry registry, String task) {
        return Counter.builder(METRIC)
                .description("Times a background task failed at something it was meant to do")
                .tag("task", task)
                .register(registry);
    }
}
