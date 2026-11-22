#!/usr/bin/env python3
"""Aggregate the append-pipelining experiment's runs (see run.sh) into one table per scenario and mode.

    aggregate.py <out-dir> [--markdown report.md] [--csv summary.csv]

Per run: decisions/s, conflicts per decision, latency percentiles of an append call, of a whole
decision (re-decisions included), of the lock wait and hold, database CPU and WAL per decision,
client allocation per decision, and the invariant check. Per scenario: the mean of the repetitions
with their min..max, and the single-round-trip mode against the shipped (off) one as a ratio of means
with the range of the per-repetition ratios (repetition r of one mode paired with repetition r of the
other, the two run back to back).
"""
import csv
import json
import math
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def pct(values, p):
    if not values:
        return float("nan")
    values = sorted(values)
    k = (len(values) - 1) * p / 100.0
    lo, hi = math.floor(k), math.ceil(k)
    return values[lo] if lo == hi else values[lo] + (values[hi] - values[lo]) * (k - lo)


def load_run(directory: Path):
    summaries = [json.loads(p.read_text()) for p in sorted(directory.glob("*-summary.json"))]
    if not summaries:
        return None
    appends, decisions = [], []
    for p in sorted(directory.glob("*-appends.csv")):
        with p.open() as f:
            appends.extend(csv.DictReader(f))
    for p in sorted(directory.glob("*-decisions.csv")):
        with p.open() as f:
            decisions.extend(csv.DictReader(f))
    verify = next(iter(directory.glob("*-verify.json")), None)
    verify = json.loads(verify.read_text()) if verify else {"decisions": -1, "violations": -1}

    window_s = max(s["window_ns"] for s in summaries) / 1e9
    committed = sum(s["decisions"] for s in summaries)
    conflicts = sum(s["conflicts"] for s in summaries)
    db = defaultdict(int)
    for s in summaries:
        for k, v in s["db"].items():
            db[k] += v
    # instance 0 measured the counters for the whole database; the others repeat them
    db = summaries[0]["db"]
    cpu_us = summaries[0].get("db_cpu_us", -1)
    allocated = sum(s["allocated_bytes"] for s in summaries)

    ms = lambda rows, key, cond=lambda r: True: [int(r[key]) / 1e6 for r in rows if cond(r)]
    held = lambda r: int(r["lock_hold_ns"]) > 0
    return {
        "decisions_s": committed / window_s,
        "background_s": sum(s["background_events"] for s in summaries) / window_s,
        "conflicts_per_decision": conflicts / committed if committed else float("nan"),
        "append_p50": pct(ms(appends, "append_ns"), 50),
        "append_p95": pct(ms(appends, "append_ns"), 95),
        "append_p99": pct(ms(appends, "append_ns"), 99),
        "decision_p50": pct(ms(decisions, "decision_ns"), 50),
        "decision_p95": pct(ms(decisions, "decision_ns"), 95),
        "decision_p99": pct(ms(decisions, "decision_ns"), 99),
        "read_p50": pct(ms(decisions, "read_ns"), 50),
        "lock_wait_p50": pct(ms(appends, "lock_wait_ns", held), 50),
        "lock_wait_p99": pct(ms(appends, "lock_wait_ns", held), 99),
        "lock_hold_p50": pct(ms(appends, "lock_hold_ns", held), 50),
        "lock_hold_p99": pct(ms(appends, "lock_hold_ns", held), 99),
        "lock_hold_mean": statistics.fmean(ms(appends, "lock_hold_ns", held)) if any(map(held, appends)) else float("nan"),
        "db_cpu_ms_per_decision": (cpu_us / 1000) / committed if committed and cpu_us >= 0 else float("nan"),
        "db_cpu_pct": cpu_us / 1e6 / window_s * 100 if cpu_us >= 0 else float("nan"),
        "wal_kb_per_decision": db["wal_bytes"] / 1024 / committed if committed else float("nan"),
        "alloc_kb_per_decision": allocated / 1024 / committed if committed else float("nan"),
        "verified": verify["decisions"],
        "violations": verify["violations"],
    }


METRICS = [
    ("decisions_s", "decisions/s", "{:.1f}"),
    ("conflicts_per_decision", "conflicts/decision", "{:.3f}"),
    ("append_p50", "append p50 ms", "{:.2f}"),
    ("append_p99", "append p99 ms", "{:.2f}"),
    ("decision_p50", "decision p50 ms", "{:.2f}"),
    ("decision_p99", "decision p99 ms", "{:.2f}"),
    ("lock_wait_p50", "lock wait p50 ms", "{:.2f}"),
    ("lock_wait_p99", "lock wait p99 ms", "{:.2f}"),
    ("lock_hold_p50", "lock hold p50 ms", "{:.3f}"),
    ("lock_hold_p99", "lock hold p99 ms", "{:.3f}"),
    ("db_cpu_ms_per_decision", "DB CPU ms/decision", "{:.3f}"),
    ("wal_kb_per_decision", "WAL KiB/decision", "{:.2f}"),
    ("alloc_kb_per_decision", "alloc KiB/decision", "{:.1f}"),
]


def main():
    out = Path(sys.argv[1])
    markdown = sys.argv[sys.argv.index("--markdown") + 1] if "--markdown" in sys.argv else None
    csv_out = sys.argv[sys.argv.index("--csv") + 1] if "--csv" in sys.argv else None

    runs = defaultdict(lambda: defaultdict(dict))  # scenario -> mode -> rep -> metrics
    order = []
    for d in sorted(out.iterdir()):
        m = re.fullmatch(r"(.+)__(off|single-round-trip)__r(\d+)", d.name)
        if not m or not (d / "done").exists():
            continue
        r = load_run(d)
        if r is None:
            continue
        scenario, mode, rep = m.group(1), m.group(2), int(m.group(3))
        if scenario not in order:
            order.append(scenario)
        runs[scenario][mode][rep] = r

    scenario_order = []
    scenario_file = out / "scenarios.txt"
    if scenario_file.exists():
        scenario_order = [l.split()[0] for l in scenario_file.read_text().splitlines() if l.strip() and not l.startswith("#")]
    order = [s for s in scenario_order if s in runs] + [s for s in order if s not in scenario_order]

    lines, rows = [], []
    violations = 0
    for scenario in order:
        modes = runs[scenario]
        none, adv = modes.get("off", {}), modes.get("single-round-trip", {})
        reps = sorted(set(none) & set(adv))
        lines.append(f"### {scenario}\n")
        lines.append(f"{len(reps)} paired repetitions. Mean (min..max) per mode; ratio = single round trip / shipped, "
                     f"mean of means with the range of per-repetition ratios.\n")
        lines.append("| metric | shipped (off) | single round trip | ratio |")
        lines.append("|---|---|---|---|")
        for key, label, fmt in METRICS:
            a = [none[r][key] for r in reps]
            b = [adv[r][key] for r in reps]
            def cell(v):
                v = [x for x in v if not math.isnan(x)]
                if not v:
                    return "n/a"
                return f"{fmt.format(statistics.fmean(v))} ({fmt.format(min(v))}..{fmt.format(max(v))})"
            pairs = [(x, y) for x, y in zip(a, b) if not math.isnan(x) and not math.isnan(y) and x > 0]
            if pairs:
                ratio_mean = statistics.fmean(y for _, y in pairs) / statistics.fmean(x for x, _ in pairs)
                rr = [y / x for x, y in pairs]
                ratio = f"{ratio_mean:.2f} ({min(rr):.2f}..{max(rr):.2f})"
            else:
                ratio = "n/a"
            lines.append(f"| {label} | {cell(a)} | {cell(b)} | {ratio} |")
            rows.append({"scenario": scenario, "metric": key,
                         "off_mean": statistics.fmean(a) if a else "", "single_mean": statistics.fmean(b) if b else "",
                         "ratio": ratio})
        lines.append("")
        bg = statistics.fmean([none[r]["background_s"] for r in reps] + [adv[r]["background_s"] for r in reps]) if reps else 0
        checked = sum(none[r]["verified"] + adv[r]["verified"] for r in reps)
        bad = sum(none[r]["violations"] + adv[r]["violations"] for r in reps)
        violations += bad
        lines.append(f"Background writers achieved {bg:.0f} events/s. Invariant: {checked} decisions checked, {bad} violations.\n")

    lines.append(f"**Invariant violations over all runs: {violations}**\n")
    text = "\n".join(lines)
    print(text)
    if markdown:
        Path(markdown).write_text(text)
    if csv_out:
        with open(csv_out, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["scenario", "metric", "off_mean", "single_mean", "ratio"])
            w.writeheader()
            w.writerows(rows)


if __name__ == "__main__":
    main()
