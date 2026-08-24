-- Phase 2: Keycloak becomes the identity provider. There is no more local password login,
-- so app_user (and its role table) is retired. A customer's login is now represented by the
-- `sub` claim of the Keycloak-issued token, recorded here once staff link the two records.
DROP TABLE app_user_role;
DROP TABLE app_user;

ALTER TABLE customer ADD COLUMN keycloak_subject VARCHAR(64);
ALTER TABLE customer ADD CONSTRAINT uk_customer_keycloak_subject UNIQUE (keycloak_subject);
