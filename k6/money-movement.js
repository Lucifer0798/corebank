import http from "k6/http";
import { check, sleep, fail } from "k6";
import { Rate } from "k6/metrics";

// Load test for the money-movement endpoints (deposit/withdraw/transfer) against a real,
// already-running CoreBank stack -- `docker compose up`, not a mock. It authenticates against the
// real Keycloak realm the same way the frontend does (password grant, corebank-web client) rather
// than a shortcut, so it exercises the actual token-validation and idempotency-key path on every
// request.
//
// Run with (no local k6 binary needed) -- joins the compose network and talks to the app and
// Keycloak by their container DNS names, since `--network host` does not reach a Docker Desktop
// host's published ports the way it does on native Linux:
//   docker run --rm -i --network corebank_default -v "${PWD}/k6:/scripts" \
//     -e BASE_URL=http://app:8080 -e KEYCLOAK_URL=http://keycloak:8080 \
//     grafana/k6 run /scripts/money-movement.js
// (On Windows Git Bash, prefix with MSYS_NO_PATHCONV=1 -- otherwise Git Bash rewrites the /scripts
// container path into a Windows host path before Docker ever sees it.)
//
// Or, if k6 is installed locally and you're running against the host-published ports directly:
//   k6 run k6/money-movement.js
//
// Override target/VUs/duration with env vars, e.g.:
//   -e VUS=50 -e DURATION=2m -e ACCOUNT_POOL_SIZE=40

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || "http://localhost:8081";
const API = `${BASE_URL}/api/v1`;
const ACCOUNT_POOL_SIZE = Number(__ENV.ACCOUNT_POOL_SIZE || 20);

const businessRejectionRate = new Rate("business_rejections");

export const options = {
    scenarios: {
        money_movement: {
            executor: "ramping-vus",
            startVUs: 0,
            stages: [
                { duration: __ENV.RAMP_UP || "30s", target: Number(__ENV.VUS || 20) },
                { duration: __ENV.DURATION || "1m", target: Number(__ENV.VUS || 20) },
                { duration: __ENV.RAMP_DOWN || "15s", target: 0 },
            ],
        },
    },
    thresholds: {
        // A 422 (insufficient funds) is an expected business outcome under concurrent withdrawals
        // draining the same account, not a system failure -- tracked separately below rather than
        // folded into this pass rate.
        checks: ["rate>0.99"],
        http_req_duration: ["p(95)<800"],
    },
};

// Cached per-VU: each VU gets its own isolated JS context, so this module-level object survives
// across iterations of one VU without becoming shared/racy state across VUs.
let tellerToken = { value: null, expiresAt: 0 };

function fetchToken(username, password) {
    const res = http.post(
        `${KEYCLOAK_URL}/realms/corebank/protocol/openid-connect/token`,
        {
            grant_type: "password",
            client_id: "corebank-web",
            username,
            password,
        },
        { tags: { name: "keycloak_token" } },
    );
    if (res.status !== 200) {
        fail(`token request for ${username} failed: ${res.status} ${res.body}`);
    }
    const body = res.json();
    return { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
}

// Refreshes 30s ahead of expiry rather than reacting to a 401, since Keycloak's dev-realm token
// lifetime is short enough that a mid-iteration expiry is otherwise routine, not exceptional.
function tellerAuthHeader() {
    if (!tellerToken.value || Date.now() > tellerToken.expiresAt - 30_000) {
        tellerToken = fetchToken("teller1", "Teller#2025");
    }
    return { Authorization: `Bearer ${tellerToken.value}`, "Content-Type": "application/json" };
}

// __VU/__ITER are only defined inside the VU context (default()), not in setup() -- referencing
// them there throws "ReferenceError: __ITER is not defined". Date.now() + a random suffix is
// unique enough on its own, so neither is actually needed for correctness.
function idempotencyKey(label) {
    return `k6-${label}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

function openFundedAccount(adminHeaders, tellerHeaders, index) {
    const customerRes = http.post(
        `${API}/customers`,
        JSON.stringify({
            firstName: "K6",
            lastName: `LoadTest${index}`,
            email: `k6-loadtest-${Date.now()}-${index}@example.com`,
            dateOfBirth: "1990-01-01",
        }),
        { headers: tellerHeaders, tags: { name: "setup_create_customer" } },
    );
    if (customerRes.status !== 201) {
        fail(`customer creation failed: ${customerRes.status} ${customerRes.body}`);
    }
    const customerId = customerRes.json("id");

    const kycRes = http.patch(
        `${API}/customers/${customerId}/kyc`,
        JSON.stringify({ kycStatus: "VERIFIED" }),
        { headers: adminHeaders, tags: { name: "setup_verify_kyc" } },
    );
    if (kycRes.status !== 200) {
        fail(`KYC verification failed: ${kycRes.status} ${kycRes.body}`);
    }

    const accountRes = http.post(
        `${API}/accounts`,
        JSON.stringify({ customerId, accountType: "SAVINGS" }),
        { headers: tellerHeaders, tags: { name: "setup_open_account" } },
    );
    if (accountRes.status !== 201) {
        fail(`account open failed: ${accountRes.status} ${accountRes.body}`);
    }
    const accountId = accountRes.json("id");

    // Pre-fund generously so randomized withdrawals during the run stay mostly successful; a
    // 422 (insufficient funds) is still expected occasionally under concurrent draining and is
    // treated as a valid business outcome, not a load-test failure.
    const fundRes = http.post(
        `${API}/accounts/${accountId}/deposits`,
        JSON.stringify({ amount: "100000.00", currency: "INR", description: "k6 seed funding" }),
        { headers: { ...tellerHeaders, "Idempotency-Key": idempotencyKey("seed") }, tags: { name: "setup_seed_funds" } },
    );
    if (fundRes.status !== 201) {
        fail(`seed funding failed: ${fundRes.status} ${fundRes.body}`);
    }

    return accountId;
}

export function setup() {
    const tellerHeaders = tellerAuthHeader();
    const adminHeaders = { ...fetchAdminAuthHeader() };

    const accountIds = [];
    for (let i = 0; i < ACCOUNT_POOL_SIZE; i++) {
        accountIds.push(openFundedAccount(adminHeaders, tellerHeaders, i));
    }
    return { accountIds };
}

function fetchAdminAuthHeader() {
    const admin = fetchToken("admin", "ChangeMe#2025!");
    return { Authorization: `Bearer ${admin.value}`, "Content-Type": "application/json" };
}

function randomAmount(min, max) {
    return (Math.random() * (max - min) + min).toFixed(2);
}

function pickTwoDistinct(pool) {
    const a = Math.floor(Math.random() * pool.length);
    let b = Math.floor(Math.random() * pool.length);
    while (b === a) {
        b = Math.floor(Math.random() * pool.length);
    }
    return [pool[a], pool[b]];
}

export default function (data) {
    const headers = tellerAuthHeader();
    const roll = Math.random();

    if (roll < 0.5) {
        const accountId = data.accountIds[Math.floor(Math.random() * data.accountIds.length)];
        const res = http.post(
            `${API}/accounts/${accountId}/deposits`,
            JSON.stringify({ amount: randomAmount(1, 500), currency: "INR", description: "k6 deposit" }),
            { headers: { ...headers, "Idempotency-Key": idempotencyKey("deposit") }, tags: { name: "deposit" } },
        );
        check(res, { "deposit posted (201)": (r) => r.status === 201 });
    } else if (roll < 0.75) {
        const accountId = data.accountIds[Math.floor(Math.random() * data.accountIds.length)];
        const res = http.post(
            `${API}/accounts/${accountId}/withdrawals`,
            JSON.stringify({ amount: randomAmount(1, 300), currency: "INR", description: "k6 withdrawal" }),
            { headers: { ...headers, "Idempotency-Key": idempotencyKey("withdrawal") }, tags: { name: "withdrawal" } },
        );
        const accepted = check(res, {
            "withdrawal posted or rejected for funds (201/422)": (r) => r.status === 201 || r.status === 422,
        });
        if (!accepted) {
            fail(`unexpected withdrawal response: ${res.status} ${res.body}`);
        }
        businessRejectionRate.add(res.status === 422);
    } else {
        const [sourceAccountId, destinationAccountId] = pickTwoDistinct(data.accountIds);
        const res = http.post(
            `${API}/transfers`,
            JSON.stringify({
                sourceAccountId,
                destinationAccountId,
                amount: randomAmount(1, 300),
                currency: "INR",
                description: "k6 transfer",
            }),
            { headers: { ...headers, "Idempotency-Key": idempotencyKey("transfer") }, tags: { name: "transfer" } },
        );
        const accepted = check(res, {
            "transfer posted or rejected for funds (201/422)": (r) => r.status === 201 || r.status === 422,
        });
        if (!accepted) {
            fail(`unexpected transfer response: ${res.status} ${res.body}`);
        }
        businessRejectionRate.add(res.status === 422);
    }

    sleep(Math.random() * 0.5 + 0.1);
}
