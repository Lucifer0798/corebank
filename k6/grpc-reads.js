import grpc from "k6/net/grpc";
import http from "k6/http";
import { check, sleep, fail } from "k6";

// Load test for the gRPC read surface, the counterpart to money-movement.js. That one drives the
// REST money-movement endpoints; this one drives the read path gRPC actually exists for, against
// a real running stack and a real Keycloak token.
//
// Run with (no local k6 binary needed) -- note the mount is the repo root, not k6/, because the
// client has to load corebank.proto from src/main/proto:
//   docker run --rm -i --network corebank_default -v "${PWD}:/repo" \
//     -e BASE_URL=http://app:8080 -e KEYCLOAK_URL=http://keycloak:8080 -e GRPC_ADDR=app:9091 \
//     grafana/k6 run /repo/k6/grpc-reads.js
// (On Windows Git Bash, prefix with MSYS_NO_PATHCONV=1 -- see money-movement.js for why.)
//
// StreamStatement is deliberately not exercised here. k6's gRPC support covers unary calls
// cleanly; server-streaming needs a different API shape, and the unary reads are the ones a
// service-to-service caller would hammer anyway. CoreBankTestcontainersIT covers the streaming
// RPC's correctness instead.

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || "http://localhost:8081";
const GRPC_ADDR = __ENV.GRPC_ADDR || "localhost:9091";
const PROTO_PATH = __ENV.PROTO_PATH || "/repo/src/main/proto";
const API = `${BASE_URL}/api/v1`;
const ACCOUNT_POOL_SIZE = Number(__ENV.ACCOUNT_POOL_SIZE || 10);

const client = new grpc.Client();
client.load([PROTO_PATH], "corebank.proto");

export const options = {
    scenarios: {
        grpc_reads: {
            executor: "ramping-vus",
            startVUs: 0,
            stages: [
                { duration: __ENV.RAMP_UP || "15s", target: Number(__ENV.VUS || 20) },
                { duration: __ENV.DURATION || "1m", target: Number(__ENV.VUS || 20) },
                { duration: __ENV.RAMP_DOWN || "10s", target: 0 },
            ],
        },
    },
    thresholds: {
        checks: ["rate>0.99"],
        // Reads off a cached/indexed path should be well inside the REST budget; if gRPC is not
        // comfortably faster than the 800ms p95 money-movement.js allows for writes, something
        // is wrong rather than merely slow.
        grpc_req_duration: ["p(95)<400"],
    },
};

function fetchToken(username, password) {
    const res = http.post(
        `${KEYCLOAK_URL}/realms/corebank/protocol/openid-connect/token`,
        { grant_type: "password", client_id: "corebank-web", username, password },
        { tags: { name: "keycloak_token" } },
    );
    if (res.status !== 200) {
        fail(`token request for ${username} failed: ${res.status} ${res.body}`);
    }
    return res.json().access_token;
}

export function setup() {
    const teller = fetchToken("teller1", "Teller#2025");
    const admin = fetchToken("admin", "ChangeMe#2025!");
    const tellerHeaders = { Authorization: `Bearer ${teller}`, "Content-Type": "application/json" };
    const adminHeaders = { Authorization: `Bearer ${admin}`, "Content-Type": "application/json" };

    // Seeded over REST on purpose: this script measures the read path, so the writes that create
    // its fixtures stay off the measured surface entirely.
    const accounts = [];
    for (let i = 0; i < ACCOUNT_POOL_SIZE; i++) {
        const customer = http.post(`${API}/customers`, JSON.stringify({
            firstName: "K6Grpc",
            lastName: `Reader${i}`,
            email: `k6-grpc-${Date.now()}-${i}@example.com`,
            dateOfBirth: "1990-01-01",
        }), { headers: tellerHeaders });
        if (customer.status !== 201) {
            fail(`customer creation failed: ${customer.status} ${customer.body}`);
        }
        const customerId = customer.json().id;

        const kyc = http.patch(`${API}/customers/${customerId}/kyc`,
            JSON.stringify({ kycStatus: "VERIFIED" }), { headers: adminHeaders });
        if (kyc.status !== 200) {
            fail(`KYC verification failed: ${kyc.status} ${kyc.body}`);
        }

        const account = http.post(`${API}/accounts`,
            JSON.stringify({ customerId, accountType: "SAVINGS" }), { headers: tellerHeaders });
        if (account.status !== 201) {
            fail(`account open failed: ${account.status} ${account.body}`);
        }
        const accountId = account.json().id;

        const deposit = http.post(`${API}/accounts/${accountId}/deposits`,
            JSON.stringify({ amount: "500.00", description: "k6 grpc seed" }),
            { headers: { ...tellerHeaders, "Idempotency-Key": `k6-grpc-seed-${Date.now()}-${i}` } });
        if (deposit.status !== 201) {
            fail(`seed deposit failed: ${deposit.status} ${deposit.body}`);
        }

        accounts.push({ accountId, customerId });
    }

    return { accounts, token: teller };
}

export default function (data) {
    // One connection per iteration: k6 has no pooled-channel primitive, and connect() on an
    // already-connected client throws. Cheap enough at this concurrency, and it also means the
    // handshake cost stays inside the measurement rather than being amortised away.
    client.connect(GRPC_ADDR, { plaintext: true });
    try {
        const meta = { authorization: `Bearer ${data.token}` };
        const pick = data.accounts[Math.floor(Math.random() * data.accounts.length)];

        const account = client.invoke(
            "corebank.v1.AccountQueryService/GetAccount",
            { account_id: pick.accountId },
            { metadata: meta, tags: { name: "GetAccount" } },
        );
        check(account, {
            "GetAccount OK": (r) => r.status === grpc.StatusOK,
            // Amounts cross as strings precisely so they survive without a double; assert that
            // rather than just the status, or the test would pass on a silently mangled balance.
            "GetAccount balance is an exact decimal string": (r) =>
                typeof r.message.balance === "string" && /^\d+\.\d{2}$/.test(r.message.balance),
        });

        const owned = client.invoke(
            "corebank.v1.AccountQueryService/ListCustomerAccounts",
            { customer_id: pick.customerId },
            { metadata: meta, tags: { name: "ListCustomerAccounts" } },
        );
        check(owned, {
            "ListCustomerAccounts OK": (r) => r.status === grpc.StatusOK,
            "ListCustomerAccounts returns the seeded account": (r) =>
                Array.isArray(r.message.accounts) && r.message.accounts.length >= 1,
        });
    } finally {
        client.close();
    }

    sleep(Math.random() * 0.3 + 0.1);
}
