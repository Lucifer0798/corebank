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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Money reserved by authorisation holds that have not yet been captured or released.
     *
     * <p>Denormalised onto the account rather than summed from the hold table on every read, and
     * that is a deliberate trade. Every path that moves money already row-locks this account, so
     * keeping the running total here is atomic for free and costs one column; summing the holds
     * instead would mean a second query inside the hot posting path, and a read that could race
     * with a hold being placed between the two statements.
     */
    @Column(name = "held_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal heldAmount = Money.ZERO;

    /**
     * How much can still be withdrawn: the balance, plus any agreed overdraft, minus whatever is
     * already promised to an outstanding authorisation.
     *
     * <p>The third term is what makes this figure honest. Without it a customer whose card was
     * authorised for a hotel at check-in could spend the same money again before the hotel
     * captured it, and the posting that finally arrived would be the one refused -- for a
     * purchase the bank had already guaranteed.
     */
    public BigDecimal availableBalance() {
        return Money.normalize(balance.add(overdraftLimit).subtract(heldAmount));
    }

    /**
     * Reserves {@code amount} against this account.
     *
     * <p>Checked against {@link #availableBalance()}, so holds compound: two authorisations for
     * 60 on a balance of 100 means the second is refused, not that both are honoured and the
     * account goes short when they are captured.
     */
    public void placeHold(BigDecimal amount) {
        assertPostable();
        if (availableBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountNumber, availableBalance(), amount);
        }
        this.heldAmount = Money.normalize(heldAmount.add(amount));
    }

    /**
     * Interest earned but not yet paid, carried at full storage scale.
     *
     * <p>This is the column the schema's {@code NUMERIC(19,4)} was chosen for. A day's interest on
     * a few thousand rupees is a fraction of a paisa; held at two decimal places it would round to
     * nothing every day and the customer would earn nothing at all.
     */
    @Column(name = "accrued_interest", nullable = false, precision = 19, scale = 4)
    private BigDecimal accruedInterest = BigDecimal.ZERO.setScale(4);

    /** The last day this account has been accrued for, so a rerun cannot pay twice. */
    @Column(name = "interest_accrued_through")
    private LocalDate interestAccruedThrough;

    /**
     * Adds one day's interest, at full scale.
     *
     * <p>Idempotent by date rather than by trust: an accrual run that crashes and restarts, or two
     * replicas ticking at once, would otherwise credit the same day twice. Returns whether anything
     * was added, so the caller can tell a real accrual from a repeat.
     */
    public boolean accrueInterestFor(LocalDate day, BigDecimal amount) {
        if (interestAccruedThrough != null && !day.isAfter(interestAccruedThrough)) {
            return false;
        }
        this.accruedInterest = accruedInterest.add(amount).setScale(4, RoundingMode.HALF_UP);
        this.interestAccruedThrough = day;
        return true;
    }

    /**
     * How much accrued interest is currently payable, rounded to real money.
     *
     * <p>Rounded <em>down</em>, deliberately. Rounding to nearest would pay out fractions the
     * account has not yet earned, and over a portfolio that is the bank inventing money; rounding
     * down pays only what is definitely owed and {@link #takeCapitalisableInterest()} leaves the
     * remainder behind to be paid next time. Nothing is lost either way -- it is carried, not
     * discarded.
     */
    public BigDecimal capitalisableInterest() {
        return accruedInterest.setScale(Money.SCALE, RoundingMode.DOWN);
    }

    /**
     * Removes and returns the payable portion, leaving the sub-paisa remainder accrued.
     *
     * <p>Subtracting exactly what was returned, rather than zeroing the field, is the whole point:
     * zeroing would quietly discard up to a paisa of the customer's money on every capitalisation,
     * twelve times a year, on every account.
     */
    public BigDecimal takeCapitalisableInterest() {
        BigDecimal payable = capitalisableInterest();
        this.accruedInterest = accruedInterest.subtract(payable).setScale(4, RoundingMode.HALF_UP);
        return payable;
    }

    /** Frees a reservation, whether it was captured, released or simply expired. */
    public void freeHold(BigDecimal amount) {
        BigDecimal remaining = Money.normalize(heldAmount.subtract(amount));
        // A negative total would mean a hold was freed twice, and would silently inflate the
        // available balance from then on. Fail loudly instead of carrying the error forward.
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException(
                    "Releasing " + amount + " would take account " + accountNumber
                            + " below zero held (currently " + heldAmount + ")");
        }
        this.heldAmount = remaining;
    }

    public boolean isCustomerAccount() {
        return accountClass == AccountClass.CUSTOMER;
    }

    /**
     * Applies one leg of a posting and returns the resulting balance.
     * A direction matching the account's normal balance increases it; the opposite decreases it.
     */
    public BigDecimal applyEntry(EntryDirection direction, BigDecimal amount) {
        return applyEntry(direction, amount, false);
    }

    /**
     * As above, but {@code allowOverdraw} lets the leg push a customer account past its agreed
     * overdraft instead of being refused.
     *
     * <p>Only reversals pass true, and the distinction is narrower than it looks. The overdraft
     * limit exists to stop the bank lending money it never agreed to lend, which is a question
     * about a <em>new</em> movement. A reversal is not a new movement: it is the withdrawal of a
     * posting that should never have existed. Refusing one because the customer has since spent
     * the money would leave the ledger permanently wrong -- the bank's own error made
     * uncorrectable by the customer's spending -- so the reversal goes through and the resulting
     * shortfall becomes a debt to collect, which is how a real bank handles it too.
     *
     * <p>Note what this does <em>not</em> bypass: {@link #assertPostable()} still applies. A
     * frozen account is frozen because somebody decided it should be, and working around that
     * silently is different from correcting the bank's own arithmetic.
     */
    public BigDecimal applyEntry(EntryDirection direction, BigDecimal amount, boolean allowOverdraw) {
        BigDecimal signed = direction == normalBalance ? amount : amount.negate();
        BigDecimal updated = Money.normalize(balance.add(signed));

        // Internal general-ledger accounts are allowed to run negative -- the bank funds them.
        // Customer accounts may only go as far negative as their agreed overdraft, less whatever
        // is already reserved: without subtracting heldAmount here a plain withdrawal could spend
        // money an outstanding authorisation had already promised, and holds would be decorative.
        if (!allowOverdraw && isCustomerAccount()
                && updated.add(overdraftLimit).subtract(heldAmount).compareTo(BigDecimal.ZERO) < 0) {
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
