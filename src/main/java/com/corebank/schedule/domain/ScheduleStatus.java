package com.corebank.schedule.domain;

public enum ScheduleStatus {

    /** Due dates are still being worked through. The only status the runner acts on. */
    ACTIVE,

    /**
     * Stopped by the bank rather than by the customer, after the transfer failed on enough
     * consecutive occasions to mean somebody should look at it. Terminal here: reinstating it is
     * a new mandate, so that the reinstatement carries its own start date and audit trail.
     */
    SUSPENDED,

    /** Every occurrence has run. Nothing further is due. */
    COMPLETED,

    /** Stopped on request. */
    CANCELLED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
