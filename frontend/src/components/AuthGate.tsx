import type { ReactNode } from "react";
import { useAuth } from "react-oidc-context";

/** Blocks rendering the app until Keycloak has either produced a session or a sign-in prompt. */
export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth();

  if (auth.isLoading) {
    return (
      <div className="centered-splash">
        <p className="muted">Connecting to Keycloak&hellip;</p>
      </div>
    );
  }

  if (auth.error) {
    return (
      <div className="centered-splash">
        <h1>Sign-in failed</h1>
        <p className="error-banner">{auth.error.message}</p>
        <button className="btn" onClick={() => auth.signinRedirect()}>
          Try again
        </button>
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <div className="centered-splash">
        <h1>CoreBank Lite</h1>
        <p className="muted">Sign in with your CoreBank identity to continue.</p>
        <button className="btn" onClick={() => auth.signinRedirect()}>
          Sign in
        </button>
      </div>
    );
  }

  return <>{children}</>;
}
