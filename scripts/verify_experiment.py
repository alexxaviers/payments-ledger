#!/usr/bin/env python3
"""Checks the failure experiment's outcome and prints a Markdown results table.

Reads from the results directory:
  failure-summary.json        k6 summary (client-side counters)
  final-invariants.json       GET /admin/invariants after the run
  final-reconciliation.json   POST /admin/reconcile after the run
  final-notifications.json    GET /stats from notifications-service
  kills.json                  how many crashes were injected

Exits non-zero if any guarantee was violated.
"""
import json
import os
import sys


def load(directory, name):
    with open(os.path.join(directory, name)) as f:
        return json.load(f)


def count(summary, metric):
    return int(summary["metrics"].get(metric, {}).get("values", {}).get("count", 0))


def main():
    d = sys.argv[1]
    k6 = load(d, "failure-summary.json")
    inv = load(d, "final-invariants.json")
    rec = load(d, "final-reconciliation.json")
    notif = load(d, "final-notifications.json")
    kills = load(d, "kills.json")

    logical = count(k6, "logical_transfers")
    completed = count(k6, "logical_completed")
    rejected = count(k6, "logical_rejected")
    lost_client = count(k6, "lost_transfers")
    resends = count(k6, "duplicate_resends")
    mismatches = count(k6, "replay_mismatches")
    retries = count(k6, "retries_after_failure")
    deposits = count(k6, "setup_deposits")

    # Server side: every completed/rejected transaction the ledger recorded, minus the setup deposits.
    server_completed = inv["completedTransactions"] - deposits
    server_rejected = inv["rejectedTransactions"]

    lost = lost_client + max(0, completed - server_completed)          # client saw success, ledger has no record
    duplicates = max(0, server_completed - completed) + max(0, server_rejected - rejected) + mismatches
    balance_mismatches = len(rec.get("balanceMismatches", [])) + inv["cachedBalanceMismatches"]
    events_missing = inv["completedTransactions"] - notif["processedEvents"]

    checks = [
        ("Transfers lost", lost, lost == 0),
        ("Transfers executed twice", duplicates, duplicates == 0),
        ("Balance mismatches (cached vs ledger)", balance_mismatches, balance_mismatches == 0),
        ("Sum of all ledger entries (cents)", inv["sumOfAllEntriesCents"], inv["sumOfAllEntriesCents"] == 0),
        ("Reconciliation problems", rec["mismatches"], rec["mismatches"] == 0),
        ("Completed transfers without a delivered event", events_missing, events_missing == 0),
    ]

    print(f"**Injected:** {kills['ledgerKills']} ledger crashes + {kills['consumerKills']} consumer crashes "
          f"(mode: {kills['mode']}) during {logical:,} transfers, {resends:,} of them deliberately re-sent "
          f"with the same key.\n")
    print("| Workload | Count |")
    print("|---|---|")
    print(f"| Logical transfers | {logical:,} ({completed:,} completed, {rejected:,} rejected for insufficient funds) |")
    print(f"| Deliberate duplicate re-sends (same key) | {resends:,} |")
    print(f"| Automatic client retries after a failed request | {retries:,} |")
    print(f"| Kafka redeliveries skipped by the consumer | {notif['duplicateDeliveriesSkipped']:,} |")
    print(f"| Events processed by notifications-service | {notif['processedEvents']:,} |")
    print()
    print("| Guarantee | Result | |")
    print("|---|---|---|")
    ok = True
    for name, value, passed in checks:
        ok &= passed
        print(f"| {name} | {value} | {'PASS' if passed else '**FAIL**'} |")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
