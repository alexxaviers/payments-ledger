// k6 workload for the failure-injection experiment (scripts/failure-experiment.sh kills pods while it runs).
//
// It behaves like a careful real client:
//   * every logical transfer gets its own Idempotency-Key;
//   * if a request fails (connection reset, timeout, 5xx while a pod dies) the client retries the SAME
//     request with the SAME key until it gets a definitive answer (201 completed / 422 rejected);
//   * additionally ~10% of transfers are deliberately sent again after succeeding, as a client would after
//     a timeout it could not distinguish from a failure. Those must replay the original answer.
//
// Counters it reports (used by scripts/verify_experiment.py):
//   logical_transfers, logical_completed, logical_rejected, lost_transfers (gave up), retries_after_failure,
//   duplicate_resends, replay_mismatches (a resend got a different answer = double execution)
import http from 'k6/http';
import { sleep, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TRANSFERS = parseInt(__ENV.TRANSFERS || '10000', 10);
const VUS = parseInt(__ENV.VUS || '40', 10);
const ACCOUNTS = parseInt(__ENV.ACCOUNTS || '20', 10);
const RETRY_RATIO = parseFloat(__ENV.RETRY_RATIO || '0.10');
const PACING_SECONDS = parseFloat(__ENV.PACING_SECONDS || '1');
const MAX_ATTEMPTS = 40;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    transfers: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: TRANSFERS,     // exactly TRANSFERS logical transfers
      maxDuration: '30m',
    },
  },
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    lost_transfers: ['count==0'],
    replay_mismatches: ['count==0'],
    logical_transfers: [`count==${TRANSFERS}`],
  },
};

const logical = new Counter('logical_transfers');
const completed = new Counter('logical_completed');
const rejected = new Counter('logical_rejected');
const lost = new Counter('lost_transfers');
const retries = new Counter('retries_after_failure');
const resends = new Counter('duplicate_resends');
const mismatches = new Counter('replay_mismatches');
const depositsMade = new Counter('setup_deposits');

// While pods are dying we expect connection errors and 5xx; they are retried, so they are not test failures.
http.setResponseCallback(http.expectedStatuses(200, 201, 422));

/** POSTs until the server gives a definitive answer. Returns the final response or null if we gave up. */
function postUntilDefinitive(url, body, key, definitive) {
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const res = http.post(url, body, {
      headers: Object.assign({ 'Idempotency-Key': key }, JSON_HEADERS),
      timeout: '10s',
      tags: { name: url.includes('/transfers') ? 'transfer' : 'setup' },
    });
    if (definitive.includes(res.status)) return res;
    if (attempt < MAX_ATTEMPTS) retries.add(1);   // status 0 = connection error, or a 5xx from a dying pod
    sleep(Math.min(0.25 * attempt, 2));
  }
  return null;
}

export function setup() {
  const ids = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const created = postUntilDefinitive(`${BASE}/accounts`, JSON.stringify({ ownerName: `chaos-${i}` }), uuidv4(), [201]);
    if (!created) fail('could not create account');
    const id = created.json('id');
    const dep = postUntilDefinitive(`${BASE}/accounts/${id}/deposit`, JSON.stringify({ amountCents: 10_000_000 }),
      uuidv4(), [200]);
    if (!dep) fail('could not deposit');
    depositsMade.add(1);
    ids.push(id);
  }
  return { ids };
}

function pick(ids) {
  return ids[Math.floor(Math.random() * ids.length)];
}

export default function (data) {
  const from = pick(data.ids);
  let to = pick(data.ids);
  while (to === from) to = pick(data.ids);
  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amountCents: 100 + Math.floor(Math.random() * 9_900) });
  const key = uuidv4();
  logical.add(1);

  const first = postUntilDefinitive(`${BASE}/transfers`, body, key, [201, 422]);
  if (!first) {
    lost.add(1);
    return;
  }
  if (first.status === 201) completed.add(1);
  else rejected.add(1);

  if (Math.random() < RETRY_RATIO) {
    // the "client timed out and retried anyway" case: same key, same body
    resends.add(1);
    const again = postUntilDefinitive(`${BASE}/transfers`, body, key, [201, 422]);
    if (!again || again.status !== first.status || again.json('transactionId') !== first.json('transactionId')) {
      mismatches.add(1);
    }
  }
  sleep(PACING_SECONDS);
}

export function handleSummary(summary) {
  return {
    'k6-results/failure-summary.json': JSON.stringify(summary, null, 2),
    stdout: textSummary(summary, { indent: ' ', enableColors: false }),
  };
}
