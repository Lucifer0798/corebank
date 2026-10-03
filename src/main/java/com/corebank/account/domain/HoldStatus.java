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
    EXPIRED,

    /**
     * Captured, and then the capture posting was reversed: the money moved and came back. Distinct
     * from RELEASED, which means nothing ever moved -- the customer ends up in the same place either
     * way, but reconciling against the merchant depends on knowing which history it was.
     */
    CAPTURE_REVERSED;

    public boolean isOutstanding() {
        return this == ACTIVE;
    }
}
