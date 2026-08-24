/**
 * Decodes the `realm_access.roles` claim out of the access token, purely to decide what the UI
 * shows. This is not a trust boundary -- the backend re-derives the same claim from the
 * signature-verified token on every request via SecurityConfig.RealmRoleConverter, so a user
 * editing this locally can change what they see, never what they can do.
 */
export type Role = "ADMIN" | "TELLER" | "CUSTOMER";

export function rolesFromAccessToken(accessToken: string | undefined): Role[] {
  if (!accessToken) {
    return [];
  }
  try {
    const payload = accessToken.split(".")[1];
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    const roles: unknown = json.realm_access?.roles;
    return Array.isArray(roles) ? roles.filter(isRole) : [];
  } catch {
    return [];
  }
}

function isRole(value: unknown): value is Role {
  return value === "ADMIN" || value === "TELLER" || value === "CUSTOMER";
}

export function isStaff(roles: Role[]): boolean {
  return roles.includes("ADMIN") || roles.includes("TELLER");
}
