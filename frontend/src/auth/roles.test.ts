import { describe, expect, it } from "vitest";
import { isAdmin, isStaff, rolesFromAccessToken } from "./roles";

/**
 * This module decides what the UI offers, not what the caller may actually do -- the backend
 * re-derives the same claim from the signature-verified token on every request. So what's worth
 * pinning here isn't security, it's that a malformed or surprising token degrades to "no roles"
 * (a customer-shaped UI) instead of throwing somewhere up the render tree.
 */

/** Builds a token the way Keycloak does: base64url, no padding, and only the payload matters. */
function tokenWithPayload(payload: unknown): string {
  const base64url = btoa(JSON.stringify(payload))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${base64url}.signature`;
}

describe("rolesFromAccessToken", () => {
  it("reads the realm_access roles out of a token", () => {
    const token = tokenWithPayload({ realm_access: { roles: ["TELLER", "ADMIN"] } });
    expect(rolesFromAccessToken(token)).toEqual(["TELLER", "ADMIN"]);
  });

  it("keeps only roles this application knows about", () => {
    // Keycloak hands out its own built-ins (offline_access, uma_authorization) alongside the
    // realm roles; letting those through would put junk in front of isStaff.
    const token = tokenWithPayload({
      realm_access: { roles: ["offline_access", "CUSTOMER", "uma_authorization"] },
    });
    expect(rolesFromAccessToken(token)).toEqual(["CUSTOMER"]);
  });

  it("decodes a payload that needs base64url translation", () => {
    // A payload whose base64 contains '-' and '_' only round-trips if the replace() pair runs;
    // without it atob throws and the caller silently loses every role.
    const token = tokenWithPayload({
      realm_access: { roles: ["ADMIN"] },
      note: "???>>>~~~ ünïcøde padding to force + and / in the raw base64",
    });
    expect(rolesFromAccessToken(token)).toEqual(["ADMIN"]);
  });

  it("returns nothing when there is no token at all", () => {
    expect(rolesFromAccessToken(undefined)).toEqual([]);
  });

  it("returns nothing rather than throwing on a malformed token", () => {
    expect(rolesFromAccessToken("not-a-jwt")).toEqual([]);
    expect(rolesFromAccessToken("header.!!!not-base64!!!.signature")).toEqual([]);
  });

  it("returns nothing when the claim is missing or the wrong shape", () => {
    expect(rolesFromAccessToken(tokenWithPayload({}))).toEqual([]);
    expect(rolesFromAccessToken(tokenWithPayload({ realm_access: {} }))).toEqual([]);
    expect(rolesFromAccessToken(tokenWithPayload({ realm_access: { roles: "ADMIN" } }))).toEqual([]);
  });
});

describe("isStaff", () => {
  it("counts admins and tellers as staff", () => {
    expect(isStaff(["ADMIN"])).toBe(true);
    expect(isStaff(["TELLER"])).toBe(true);
    expect(isStaff(["CUSTOMER", "TELLER"])).toBe(true);
  });

  it("does not count a customer, or nobody at all, as staff", () => {
    expect(isStaff(["CUSTOMER"])).toBe(false);
    expect(isStaff([])).toBe(false);
  });
});

describe("isAdmin", () => {
  it("counts only admins", () => {
    expect(isAdmin(["ADMIN"])).toBe(true);
    expect(isAdmin(["CUSTOMER", "ADMIN"])).toBe(true);
  });

  it("does not count a teller", () => {
    // The whole point of the helper: a teller is staff but must not be offered a reversal, so
    // isStaff and isAdmin have to disagree here or the narrower check buys nothing.
    expect(isStaff(["TELLER"])).toBe(true);
    expect(isAdmin(["TELLER"])).toBe(false);
  });

  it("does not count a customer, or nobody at all", () => {
    expect(isAdmin(["CUSTOMER"])).toBe(false);
    expect(isAdmin([])).toBe(false);
  });
});
