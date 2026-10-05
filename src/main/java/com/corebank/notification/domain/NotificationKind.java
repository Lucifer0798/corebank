package com.corebank.notification.domain;

public enum NotificationKind {

    /** Money moved, or a movement was reversed. Carries the posting's reference, status and leg. */
    TRANSACTION,

    /** A standing instruction's occurrence was refused. Nothing moved, so there is no posting. */
    SCHEDULED_TRANSFER_FAILED,

    /** As above, and the refusal was one too many: the instruction has been stopped. */
    SCHEDULED_TRANSFER_SUSPENDED
}
