package com.corebank.account.domain;

import com.corebank.common.Money;
import com.corebank.common.domain.AuditableEntity;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.InsufficientFundsException;
import com.corebank.customer.domain.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * A ledger account. Customer accounts are liabilities of the bank (normal balance CREDIT);
 * the internal cash account is an asset (normal balance DEBIT). Balance changes are applied
 * only through {@link #applyEntry}, which keeps the sign convention in one place.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "account")
public class Account extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_class", nullable = false, length = 20)
    private AccountClass accountClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 10)
    private EntryDirection normalBalance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = Money.ZERO;

    @Column(name = "overdraft_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal overdraftLimit = Money.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** How much can still be withdrawn: the balance plus any agreed overdraft. */
    public BigDecimal availableBalance() {
        return Money.normalize(balance.add(overdraftLimit));
    }

    public boolean isCustomerAccount() {
        return accountClass == AccountClass.CUSTOMER;
    }

    /**
     * Applies one leg of a posting and returns the resulting balance.
     * A direction matching the account's normal balance increases it; the opposite decreases it.
     */
    public BigDecimal applyEntry(EntryDirection direction, BigDecimal amount) {
        BigDecimal signed = direction == normalBalance ? amount : amount.negate();
        BigDecimal updated = Money.normalize(balance.add(signed));

        // Internal general-ledger accounts are allowed to run negative -- the bank funds them.
        // Customer accounts may only go as far negative as their agreed overdraft.
        if (isCustomerAccount() && updated.add(overdraftLimit).compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(accountNumber, availableBalance(), amount);
        }
        this.balance = updated;
        return updated;
    }

    /** Guards that must hold before an account can take part in a posting. */
    public void assertPostable() {
        if (status == AccountStatus.CLOSED) {
            throw new BusinessRuleException("ACCOUNT_CLOSED", "Account " + accountNumber + " is closed");
        }
        if (status == AccountStatus.FROZEN) {
            throw new BusinessRuleException("ACCOUNT_FROZEN", "Account " + accountNumber + " is frozen");
        }
    }

    public void assertCurrency(String expected) {
        if (!currency.equals(expected)) {
            throw new BusinessRuleException("CURRENCY_MISMATCH",
                    "Account " + accountNumber + " is held in " + currency + ", not " + expected);
        }
    }
}
