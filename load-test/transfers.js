// k6 load test for POST /transfers.
//
//   k6 run load-test/transfers.js                         # 100 VUs for 60s against localhost:8080
//   BASE_URL=http://host:8080 VUS=50 DURATION=30s k6 run load-test/transfers.js
//
// setup() creates ACCOUNTS accounts and funds each with $10,000. Every iteration moves a random amount
// between two random accounts with a fresh Idempotency-Key. 422 (insufficient funds) is a valid business
// outcome, not an error. teardown() asks the service to verify its invariants and checks that customer
// money was neither created nor destroyed.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = parseInt(__ENV.ACCOUNTS || '50', 10);
const OPENING_BALANCE = parseInt(__ENV.OPENING_BALANCE || '1000000', 10); // $10,000.00 in cents
const REPLAY_RATIO = parseFloat(__ENV.REPLAY_RATIO || '0');                // share of transfers re-sent with the same key
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  vus: parseInt(__ENV.VUS || '100', 10),
  duration: __ENV.DURATION || '60s',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'http_req_failed': ['rate==0'],                  // any 5xx / unexpected status fails the run
    'http_req_duration{name:transfer}': ['p(99)<2000'],
    'http_reqs{name:transfer}': ['count>0'],
    'checks': ['rate==1'],
  },
};

const completed = new Counter('transfers_completed');
const rejected = new Counter('transfers_rejected');
const replays = new Counter('transfers_replayed');

http.setResponseCallback(http.expectedStatuses(200, 201, 422));

export function setup() {
  const ids = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const res = http.post(`${BASE}/accounts`, JSON.stringify({ ownerName: `k6-${i}` }), { headers: JSON_HEADERS });
    if (res.status !== 201) fail(`create account failed: ${res.status} ${res.body}`);
    const id = res.json('id');
    const dep = http.post(`${BASE}/accounts/${id}/deposit`, JSON.stringify({ amountCents: OPENING_BALANCE }),
      { headers: Object.assign({ 'Idempotency-Key': uuidv4() }, JSON_HEADERS) });
    if (dep.status !== 200) fail(`deposit failed: ${dep.status} ${dep.body}`);
    ids.push(id);
  }
  const before = http.get(`${BASE}/admin/invariants`);
  if (before.status !== 200) fail(`invariants failed before the test: ${before.body}`);
  return { ids, customerTotalBefore: before.json('customerBalanceTotalCents') };
}

function pick(ids) {
  return ids[Math.floor(Math.random() * ids.length)];
}

export default function (data) {
  const from = pick(data.ids);
  let to = pick(data.ids);
  while (to === from) to = pick(data.ids);
  const amountCents = 100 + Math.floor(Math.random() * 9_900); // $1.00 .. $99.99

  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amountCents });
  const params = { headers: Object.assign({ 'Idempotency-Key': uuidv4() }, JSON_HEADERS), tags: { name: 'transfer' } };
  const res = http.post(`${BASE}/transfers`, body, params);
  check(res, { 'transfer completed or rejected': (r) => r.status === 201 || r.status === 422 });
  if (res.status === 201) completed.add(1);
  else if (res.status === 422) rejected.add(1);

  if (REPLAY_RATIO > 0 && Math.random() < REPLAY_RATIO) {
    const again = http.post(`${BASE}/transfers`, body, Object.assign({}, params, { tags: { name: 'replay' } }));
    check(again, {
      'replay returns the original answer': (r) => r.status === res.status
        && r.headers['Idempotent-Replayed'] === 'true'
        && r.json('transactionId') === res.json('transactionId'),
    });
    replays.add(1);
  }
}

export function teardown(data) {
  const res = http.get(`${BASE}/admin/invariants`);
  check(res, {
    'invariants hold after load': (r) => r.status === 200 && r.json('ok') === true,
    'customer money conserved': (r) => r.json('customerBalanceTotalCents') === data.customerTotalBefore,
  });
  console.log(`invariants after load: ${res.body}`);
}

export function handleSummary(summary) {
  return {
    'k6-results/summary.json': JSON.stringify(summary, null, 2),
    stdout: textSummary(summary, { indent: ' ', enableColors: false }),
  };
}
