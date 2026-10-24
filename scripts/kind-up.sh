#!/usr/bin/env bash
# One command to run the whole system on a local Kubernetes cluster (kind).
#
#   ./scripts/kind-up.sh                 # ledger x3, notifications, Postgres, Kafka
#   MONITORING=1 ./scripts/kind-up.sh    # also kube-prometheus-stack (Prometheus + Grafana on :3000)
#
# Requires: docker, kind, kubectl (and helm when MONITORING=1).
# Afterwards: ledger API on http://localhost:8080, notifications on http://localhost:8081.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${CLUSTER:-ledger}"
NS=ledger

if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --config k8s/kind-config.yaml --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

echo "==> Building jars and images"
./mvnw -B -q -DskipTests package
docker build -q -t payments-ledger/ledger-service:local ledger-service
docker build -q -t payments-ledger/notifications-service:local notifications-service
kind load docker-image --name "$CLUSTER" payments-ledger/ledger-service:local payments-ledger/notifications-service:local

echo "==> Infrastructure (Postgres, Kafka)"
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/app/secret.yaml -f k8s/app/configmap.yaml
kubectl apply -f k8s/infra/
kubectl -n $NS rollout status statefulset/postgres --timeout=180s
kubectl -n $NS rollout status statefulset/kafka --timeout=180s

echo "==> Applications"
kubectl apply -f k8s/app/
# pick up freshly loaded images when re-running the script
kubectl -n $NS rollout restart deployment/ledger deployment/notifications >/dev/null
kubectl -n $NS rollout status deployment/ledger --timeout=300s
kubectl -n $NS rollout status deployment/notifications --timeout=300s

if [[ "${MONITORING:-0}" == "1" ]]; then
  echo "==> kube-prometheus-stack"
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
  helm repo update >/dev/null
  helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
    --namespace monitoring --create-namespace --wait \
    --set grafana.service.type=NodePort --set grafana.service.nodePort=30300 \
    --set grafana.adminPassword=admin \
    --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false \
    --set prometheus.prometheusSpec.ruleSelectorNilUsesHelmValues=false
  kubectl apply -f k8s/monitoring/
  kubectl -n monitoring create configmap ledger-dashboard \
    --from-file=ledger.json=observability/grafana/dashboards/ledger.json \
    --dry-run=client -o yaml | kubectl label --local -f - grafana_dashboard=1 -o yaml | kubectl apply -f -
  echo "Grafana: http://localhost:3000 (admin/admin), dashboard 'Payments Ledger'"
fi

kubectl -n $NS get pods
echo
echo "Ledger API:     http://localhost:8080   (try: curl localhost:8080/admin/invariants)"
echo "Notifications:  http://localhost:8081/stats"
