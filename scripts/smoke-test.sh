#!/usr/bin/env bash
# End-to-end check against a running system: transfer money, see the event reach the notifications
# service, and confirm the books reconcile.
#   BASE=http://localhost:8080 NOTIF=http://localhost:8081 ./scripts/smoke-test.sh
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
NOTIF="${NOTIF:-http://localhost:8081}"
J='Content-Type: application/json'

json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

before=$(curl -fsS "$NOTIF/stats" | json "['processedEvents']")

a=$(curl -fsS -XPOST "$BASE/accounts" -H "$J" -d '{"ownerName":"smoke-a"}' | json "['id']")
b=$(curl -fsS -XPOST "$BASE/accounts" -H "$J" -d '{"ownerName":"smoke-b"}' | json "['id']")
curl -fsS -XPOST "$BASE/accounts/$a/deposit" -H "$J" -d '{"amountCents":5000}' >/dev/null
key=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
status=$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$BASE/transfers" -H "$J" -H "Idempotency-Key: $key" \
  -d "{\"fromAccountId\":\"$a\",\"toAccountId\":\"$b\",\"amountCents\":1500}")
[[ "$status" == "201" ]] || { echo "transfer returned $status"; exit 1; }

balance=$(curl -fsS "$BASE/accounts/$b" | json "['balanceCents']")
[[ "$balance" == "1500" ]] || { echo "unexpected balance $balance"; exit 1; }
echo "transfer OK (b has $balance cents)"

# deposit + transfer = 2 events
for i in $(seq 1 30); do
  now=$(curl -fsS "$NOTIF/stats" | json "['processedEvents']")
  if (( now >= before + 2 )); then echo "events delivered to notifications-service ($before -> $now)"; break; fi
  sleep 2
done
(( now >= before + 2 )) || { echo "events not delivered"; exit 1; }

curl -fsS -XPOST "$BASE/admin/reconcile" >/dev/null && echo "reconciliation OK"
