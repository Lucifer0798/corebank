package com.corebank.schedule.domain;

/** What happened to a standing instruction after it was set up. Its creation is on its own row. */
public enum ScheduleEventType {

    /** Stopped on request. */
    CANCELLED,

    /** Restarted by staff after a suspension. */
    RESUMED,

    /** Stopped by the runner: too many refusals in a row, or its last occurrence was refused. */
    SUSPENDED
}
