package com.corebank.auth.domain;

/**
 * CUSTOMER can only read their own accounts. TELLER moves money on behalf of customers.
 * ADMIN additionally manages users and account lifecycle.
 */
public enum Role {
    CUSTOMER,
    TELLER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
