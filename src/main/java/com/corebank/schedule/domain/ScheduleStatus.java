package com.corebank.schedule.domain;

public enum ScheduleStatus {

    /** Due dates are still being worked through. The only status the runner acts on. */
    ACTIVE,

    /**
     * Stopped by the bank rather than by the customer, after the transfer failed on enough
     * consecutive occasions to mean somebody should look at it -- or because its last occurrence
     * failed. Staff can resume it once the cause is fixed, from its next occurrence; see
     * {@code ScheduledTransfer#resume}.
     *
     * <p>This used to be final, with reinstatement meaning a new mandate "so that it carries its
     * own start date". That cost more than it bought: the replacement lost the original's history,
     * and its timetable started over -- a rent payment set up for the 31st, recreated on the 5th,
     * moved to the 5th. Resuming keeps the anchor date, so the 31st stays the 31st.
     */
    SUSPENDED,

    /** Every occurrence has run. Nothing further is due. */
    COMPLETED,

    /** Stopped on request. */
    CANCELLED
}
