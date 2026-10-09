-- Who made each posting.
--
-- Nothing recorded it. When a teller took a cash deposit or paid out a withdrawal, which teller
-- did it was not kept anywhere -- the thing a till is reconciled against at the end of the day, and
-- the first question when cash is disputed. An admin's reversal kept its reason, as the posting's
-- description, but not who unwound the money.
--
-- Set once, when the posting is written: the caller's token for anything a person did, or
-- system:<job> for the standing-order runner, interest capitalisation and the dev seeder. Nullable
-- only because postings from before this cannot be attributed after the fact; the application never
-- writes a new one without it.

ALTER TABLE bank_transaction ADD COLUMN initiated_by_subject VARCHAR(64);
ALTER TABLE bank_transaction ADD COLUMN initiated_by_name VARCHAR(255);
