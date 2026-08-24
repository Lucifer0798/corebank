import type { AuthProviderProps } from "react-oidc-context";

/**
 * Points the SPA at Keycloak directly -- login never goes through the CoreBank API. See
 * keycloak/corebank-realm.json in the repo root for the "corebank-web" client this matches:
 * public, PKCE-only, redirecting back to whatever origin the app is actually running on.
 */
const authority =
  import.meta.env.VITE_OIDC_AUTHORITY ?? "http://localhost:8081/realms/corebank";
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? "corebank-web";

export const oidcConfig: AuthProviderProps = {
  authority,
  client_id: clientId,
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile email",
  automaticSilentRenew: true,
  onSigninCallback: () => {
    // Strips the ?code=&state= Keycloak appended after a successful redirect, so a refresh
    // doesn't try to replay a spent authorization code.
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
