#!/usr/bin/env bash
# The headline experiment: crash ledger pods over and over while 10,000 transfers (10% of them deliberately
# retried with the same Idempotency-Key) are flowing, then prove nothing was lost, duplicated or mismatched.
#
#   ./scripts/kind-up.sh && ./scripts/failure-experiment.sh
#
# Each "kill" is a real crash: SIGKILL to the JVM (no graceful shutdown, in-flight DB transactions are
# aborted, in-flight HTTP requests get connection resets), followed by deleting the pod so the Deployment
# replaces it immediately instead of applying CrashLoopBackOff delays. Every 10th kill also crashes the
# notifications consumer, forcing Kafka redeliveries.
#
# KILL_MODE=delete uses plain `kubectl delete pod` (graceful SIGTERM) instead, as in the original guide.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${CLUSTER:-ledger}"
NODE="${CLUSTER}-control-plane"
NS=ledger
KILLS="${KILLS:-50}"
INTERVAL="${INTERVAL:-5}"
KILL_MODE="${KILL_MODE:-sigkill}"
OUT=k6-results
mkdir -p "$OUT"

ready_pod() {   # prints one random Ready pod with the given app label, or nothing
  kubectl -n $NS get pods -l "app=$1" -o json | python3 -c '
import json, random, sys
pods = [p["metadata"]["name"] for p in json.load(sys.stdin)["items"]
        if p["metadata"].get("deletionTimestamp") is None
        and any(c["type"] == "Ready" and c["status"] == "True" for c in p["status"].get("conditions", []))]
print(random.choice(pods) if pods else "")'
}

crash() {   # crash <pod>: SIGKILL the container process on the node, then replace the pod
  local pod="$1"
  if [[ "$KILL_MODE" == "sigkill" ]]; then
    local cid pid
    cid=$(kubectl -n $NS get pod "$pod" -o jsonpath='{.status.containerStatuses[0].containerID}' | sed 's#^.*://##')
    pid=$(docker exec "$NODE" crictl inspect "$cid" | python3 -c 'import json,sys; print(json.load(sys.stdin)["info"]["pid"])')
    docker exec "$NODE" kill -9 "$pid"
  fi
  kubectl -n $NS delete pod "$pod" --wait=false >/dev/null
}

echo "==> Starting k6: ${TRANSFERS:-10000} transfers, ${RETRY_RATIO:-0.10} deliberately retried"
k6 run --quiet load-test/failure-test.js > "$OUT/failure-k6.log" 2>&1 &
K6_PID=$!
sleep 15   # let setup finish and traffic ramp up

echo "==> Injecting $KILLS crashes (mode=$KILL_MODE), one every ${INTERVAL}s"
kills=0; consumer_kills=0
: > "$OUT/kills.log"
while (( kills < KILLS )); do
  if ! kill -0 $K6_PID 2>/dev/null; then echo "k6 finished before all kills were injected"; break; fi
  pod=$(ready_pod ledger)
  if [[ -z "$pod" ]]; then sleep 1; continue; fi
  crash "$pod"
  kills=$((kills + 1))
  echo "$(date +%T) kill #$kills ledger pod $pod" | tee -a "$OUT/kills.log"
  if (( kills % 10 == 0 )); then
    cpod=$(ready_pod notifications)
    if [[ -n "$cpod" ]]; then
      crash "$cpod"; consumer_kills=$((consumer_kills + 1))
      echo "$(date +%T)   + crashed notifications pod $cpod" | tee -a "$OUT/kills.log"
    fi
  fi
  sleep "$INTERVAL"
done
echo "{\"ledgerKills\": $kills, \"consumerKills\": $consumer_kills, \"mode\": \"$KILL_MODE\"}" > "$OUT/kills.json"

echo "==> Waiting for k6 to finish"
set +e; wait $K6_PID; K6_EXIT=$?; set -e
tail -n 40 "$OUT/failure-k6.log"

echo "==> Waiting for the system to settle (all pods ready, outbox drained, consumer caught up)"
kubectl -n $NS rollout status deployment/ledger --timeout=300s
kubectl -n $NS rollout status deployment/notifications --timeout=300s
for i in $(seq 1 90); do
  curl -fsS localhost:8080/admin/invariants -o "$OUT/final-invariants.json" 2>/dev/null || { sleep 2; continue; }
  curl -fsS localhost:8081/stats -o "$OUT/final-notifications.json" 2>/dev/null || { sleep 2; continue; }
  completed=$(python3 -c "import json; print(json.load(open('$OUT/final-invariants.json'))['completedTransactions'])")
  processed=$(python3 -c "import json; print(json.load(open('$OUT/final-notifications.json'))['processedEvents'])")
  if [[ "$completed" == "$processed" ]] && curl -fsS -XPOST localhost:8080/admin/reconcile -o "$OUT/final-reconciliation.json"; then
    break
  fi
  sleep 2
done
curl -sS -XPOST localhost:8080/admin/reconcile -o "$OUT/final-reconciliation.json" || true

python3 scripts/verify_experiment.py "$OUT" | tee "$OUT/experiment-results.md"
VERIFY_EXIT=${PIPESTATUS[0]}
if (( K6_EXIT != 0 || VERIFY_EXIT != 0 )); then
  echo "Experiment FAILED (k6 exit $K6_EXIT, verify exit $VERIFY_EXIT)"; exit 1
fi
echo "Experiment PASSED"
