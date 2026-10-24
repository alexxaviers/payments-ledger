#!/usr/bin/env bash
# Deletes the local kind cluster and everything in it.
set -euo pipefail
kind delete cluster --name "${CLUSTER:-ledger}"
