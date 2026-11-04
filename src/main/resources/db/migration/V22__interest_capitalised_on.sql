-- The day each account's interest was last paid out.
--
-- Capitalisation used to happen on the 1st and only on the 1st, so a 1st with no run at all -- a
-- deploy down overnight, a crash loop, a database outage -- cost every account that month's
-- payment, paid with the next month's a month late. Now an account is due from the 1st until it
-- has been paid that month, and this is how the run knows it has been.
--
-- Back-filled from each account's latest interest posting, as a UTC date because that is the day
-- the run reckons in. Without it every account would read as never paid, and every one already
-- paid this month would be paid again, early, the day this deploys. An account never paid stays
-- null, and is due once it has been open since the start of a month.

ALTER TABLE account ADD COLUMN interest_capitalised_on DATE;

UPDATE account SET interest_capitalised_on = (
    SELECT CAST(MAX(t.posted_at) AT TIME ZONE 'UTC' AS DATE)
      FROM ledger_entry e
      JOIN bank_transaction t ON t.id = e.transaction_id
     WHERE e.account_id = account.id
       AND t.type = 'INTEREST'
);
