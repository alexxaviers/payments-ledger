#!/usr/bin/env python3
"""Turn a k6 summary.json (+ optional invariants.json) into a Markdown results table.

    python3 load-test/summarize.py k6-results/summary.json k6-results/invariants.json
"""
import json
import sys


def metric(summary, name):
    return summary["metrics"].get(name, {}).get("values", {})


def main():
    summary = json.load(open(sys.argv[1]))
    invariants = json.load(open(sys.argv[2])) if len(sys.argv) > 2 else None

    reqs = metric(summary, "http_reqs{name:transfer}")
    dur = metric(summary, "http_req_duration{name:transfer}")
    failed = metric(summary, "http_req_failed")
    completed = metric(summary, "transfers_completed").get("count", 0)
    rejected = metric(summary, "transfers_rejected").get("count", 0)
    vus = metric(summary, "vus_max").get("value", "?")

    rows = [
        ("Virtual users", f"{vus}"),
        ("Transfer requests", f"{int(reqs.get('count', 0)):,}"),
        ("Throughput", f"{reqs.get('rate', 0):,.0f} transfers/sec"),
        ("Latency p50 / p95 / p99", f"{dur.get('med', 0):.1f} / {dur.get('p(95)', 0):.1f} / {dur.get('p(99)', 0):.1f} ms"),
        ("Max latency", f"{dur.get('max', 0):.1f} ms"),
        ("Completed / rejected (insufficient funds)", f"{int(completed):,} / {int(rejected):,}"),
        ("Errors (5xx or unexpected status)", f"{failed.get('passes', 0)}"),
    ]
    if invariants is not None:
        rows += [
            ("Invariants OK after run", "yes" if invariants.get("ok") else "**NO**"),
            ("Sum of all ledger entries", f"{invariants.get('sumOfAllEntriesCents')}"),
            ("Cached balance mismatches", f"{invariants.get('cachedBalanceMismatches')}"),
            ("Negative customer balances", f"{invariants.get('negativeCustomerBalances')}"),
        ]

    print("| Metric | Result |")
    print("|---|---|")
    for k, v in rows:
        print(f"| {k} | {v} |")


if __name__ == "__main__":
    main()
