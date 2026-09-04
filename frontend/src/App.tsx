import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { AuthGate } from "./components/AuthGate";
import { Layout } from "./components/Layout";
import { isStaff, rolesFromAccessToken } from "./auth/roles";
import { CustomersPage } from "./pages/CustomersPage";
import { CustomerDetailPage } from "./pages/CustomerDetailPage";
import { AccountDetailPage } from "./pages/AccountDetailPage";
import { MyAccountsPage } from "./pages/MyAccountsPage";
import { SearchPage } from "./pages/SearchPage";
import { TransactionDetailPage } from "./pages/TransactionDetailPage";

function Home() {
  const auth = useAuth();
  const staff = isStaff(rolesFromAccessToken(auth.user?.access_token));
  return <Navigate to={staff ? "/customers" : "/my-accounts"} replace />;
}

function App() {
  return (
    <AuthGate>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Home />} />
          <Route path="/customers" element={<CustomersPage />} />
          <Route path="/customers/:customerId" element={<CustomerDetailPage />} />
          <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
          <Route path="/my-accounts" element={<MyAccountsPage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/transactions/:reference" element={<TransactionDetailPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </AuthGate>
  );
}

export default App;
