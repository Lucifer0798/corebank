package com.corebank.account.domain;

public enum HoldStatus {

    /** Reserved and outstanding. The only status that counts against the available balance. */
    ACTIVE,

    /** The merchant claimed it: a real posting exists, and the reservation is gone. */
    CAPTURED,

    /** Given up on deliberately, before anything was claimed. */
    RELEASED,

    /** Nobody captured it in time. Indistinguishable from a release to the balance, but not to
     *  whoever is reconciling later -- a merchant that habitually lets holds expire is a fact
     *  worth keeping. */
    EXPIRED;

    public boolean isOutstanding() {
        return this == ACTIVE;
    }
}
