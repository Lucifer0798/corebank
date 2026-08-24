import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { rolesFromAccessToken, isStaff } from "../auth/roles";

export function Layout() {
  const auth = useAuth();
  const roles = rolesFromAccessToken(auth.user?.access_token);
  const staff = isStaff(roles);
  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.sub;

  return (
    <>
      <nav className="app-nav">
        <div style={{ display: "flex", alignItems: "center", gap: "2rem" }}>
          <span className="app-nav__brand">CoreBank Lite</span>
          <div className="app-nav__links">
            {staff ? (
              <NavLink to="/customers" className={({ isActive }) => (isActive ? "active" : "")}>
                Customers
              </NavLink>
            ) : (
              <NavLink to="/my-accounts" className={({ isActive }) => (isActive ? "active" : "")}>
                My accounts
              </NavLink>
            )}
          </div>
        </div>
        <div className="app-nav__user">
          <span>
            {username} &middot; {roles.join(", ") || "no role"}
          </span>
          <button className="btn btn--secondary" onClick={() => auth.signoutRedirect()}>
            Sign out
          </button>
        </div>
      </nav>
      <main className="app-main">
        <Outlet />
      </main>
    </>
  );
}
