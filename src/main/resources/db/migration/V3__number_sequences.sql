-- Human-facing customer and account numbers come from real sequences so they are
-- gap-tolerant but never duplicated, even under concurrent onboarding.
CREATE SEQUENCE customer_number_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE account_number_seq START WITH 1 INCREMENT BY 1;
