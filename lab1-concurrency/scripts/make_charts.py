"""Builds the report charts from results.csv.

    python scripts/make_charts.py [results.csv] [--physical-cores N] [--out charts]

For every task and configuration the median of the runs is used.
Writes PNG charts and summary.md (the tables) into the output folder.
Needs: pip install matplotlib
"""
import argparse
import csv
import os
import statistics
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"

plt.rcParams.update({
    "figure.dpi": 150, "savefig.dpi": 200, "font.size": 9,
    "axes.edgecolor": INK2, "axes.labelcolor": INK, "axes.titlesize": 10,
    "axes.titleweight": "bold", "axes.grid": True, "grid.color": GRID,
    "grid.linewidth": 0.6, "axes.spines.top": False, "axes.spines.right": False,
    "xtick.color": INK2, "ytick.color": INK2, "legend.frameon": False,
    "lines.linewidth": 2, "lines.markersize": 5,
})


def load(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def medians(rows, task, variant_prefix=None):
    """{threads: (time, process_mb, heap_mb)} using the median of the runs."""
    by = defaultdict(list)
    for r in rows:
        if r["task"] != task:
            continue
        if variant_prefix and not r["variant"].startswith(variant_prefix):
            continue
        by[int(r["threads"])].append(r)
    out = {}
    for t, rs in sorted(by.items()):
        out[t] = (statistics.median(float(r["time_ms"]) for r in rs),
                  statistics.median(float(r["peak_process_mb"]) for r in rs),
                  statistics.median(float(r["peak_heap_mb"]) for r in rs),
                  len(rs))
    return out


def mark_cores(ax, physical, logical):
    for x, label in ((physical, "physical cores"), (logical, "logical processors")):
        if x:
            ax.axvline(x, color=INK2, linestyle=":", linewidth=1)
            ax.annotate(f"{label} = {x}", (x, 0.03), xycoords=("data", "axes fraction"),
                        xytext=(-3, 0), textcoords="offset points", fontsize=7, color=INK2,
                        rotation=90, ha="right", va="bottom")


def xaxis_threads(ax, xs, label="Threads"):
    ax.set_xscale("log", base=2)
    ax.set_xticks(xs)
    ax.set_xticklabels([str(x) for x in xs])
    ax.minorticks_off()
    ax.set_xlabel(label)


def three_charts(name, title, series, xlabel, out, physical, logical, ideal=True):
    """series: list of (label, color, medians dict). Writes time, speed-up, memory PNGs."""
    xs_all = sorted({t for _, _, m in series for t in m})

    # time
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    for label, color, m in series:
        xs = sorted(m)
        ax.plot(xs, [m[x][0] for x in xs], color=color, marker="o", label=label)
    xaxis_threads(ax, xs_all, xlabel)
    ax.set_ylabel("Time (ms)")
    ax.set_ylim(bottom=0)
    ax.set_title(f"{title}: execution time")
    mark_cores(ax, physical, logical)
    if len(series) > 1:
        ax.legend(loc="best")
    fig.tight_layout()
    fig.savefig(os.path.join(out, f"{name}_time.png"))
    plt.close(fig)

    # speed-up
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    for label, color, m in series:
        xs = sorted(m)
        base = m[xs[0]][0]
        ax.plot(xs, [base / m[x][0] for x in xs], color=color, marker="o", label=label)
    if ideal:
        cap = logical or max(xs_all)
        ideal_x = sorted({x for x in xs_all if x < cap} | {cap})
        ax.plot(ideal_x, ideal_x, color=INK2, linestyle="--", linewidth=1, label="ideal (linear)")
    xaxis_threads(ax, xs_all, xlabel)
    ax.set_ylabel("Speed-up vs 1 thread")
    ax.set_ylim(bottom=0)
    ax.set_title(f"{title}: speed-up")
    mark_cores(ax, physical, logical)
    ax.legend(loc="best")
    fig.tight_layout()
    fig.savefig(os.path.join(out, f"{name}_speedup.png"))
    plt.close(fig)

    # memory
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    if len(series) == 1:
        label, _, m = series[0]
        xs = sorted(m)
        ax.plot(xs, [m[x][1] for x in xs], color=BLUE, marker="o", label="process (peak working set)")
        ax.plot(xs, [m[x][2] for x in xs], color=ORANGE, marker="o", label="Java heap (peak)")
    else:
        for label, color, m in series:
            xs = sorted(m)
            ax.plot(xs, [m[x][1] for x in xs], color=color, marker="o", label=f"{label} (process)")
    xaxis_threads(ax, xs_all, xlabel)
    ax.set_ylabel("Peak memory (MB)")
    ax.set_ylim(bottom=0)
    ax.set_title(f"{title}: peak memory")
    ax.legend(loc="best")
    fig.tight_layout()
    fig.savefig(os.path.join(out, f"{name}_memory.png"))
    plt.close(fig)


def table(m, xlabel="Threads"):
    if not m:
        return "_no data_\n"
    xs = sorted(m)
    base = m[xs[0]][0]
    lines = [f"| {xlabel} | Time (ms) | Speed-up | Peak process (MB) | Peak heap (MB) | Runs |",
             "|---:|---:|---:|---:|---:|---:|"]
    for x in xs:
        t, p, h, n = m[x]
        lines.append(f"| {x} | {t:.0f} | {base / t:.2f} | {p:.0f} | {h:.0f} | {n} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="?", default="results.csv")
    ap.add_argument("--physical-cores", type=int, default=0)
    ap.add_argument("--out", default="charts")
    a = ap.parse_args()
    rows = load(a.csv)
    os.makedirs(a.out, exist_ok=True)
    logical = int(rows[0]["logical_cpus"]) if rows else 0
    md = ["# Results (median of runs)\n"]

    m1a = medians(rows, "1a")
    if m1a:
        three_charts("task1a", "Task 1a", [("Task 1a", BLUE, m1a)], "Threads",
                     a.out, a.physical_cores, logical)
        md += ["## Task 1a\n", table(m1a)]

    m1c = medians(rows, "1c", "batch=10000;cap=100")
    if m1c:
        three_charts("task1c", "Task 1c", [("Task 1c", BLUE, m1c)], "Workers",
                     a.out, a.physical_cores, logical)
        if m1a:
            three_charts("task1c_vs_1a", "Task 1c vs 1a",
                         [("1a: split file", BLUE, m1a), ("1c: reader + queue", ORANGE, m1c)],
                         "Threads / workers", a.out, a.physical_cores, logical, ideal=False)
        md += ["## Task 1c\n", table(m1c, "Workers")]

    extras = [r for r in rows if r["task"] == "1c" and not r["variant"].startswith("batch=10000;cap=100")]
    if extras:
        md += ["## Task 1c extra experiments (question 6)\n",
               "| Variant | Workers | Time (ms) | Peak process (MB) | Peak heap (MB) | Check |",
               "|---|---:|---:|---:|---:|---|"]
        md += [f"| {r['variant']} | {r['threads']} | {float(r['time_ms']):.0f} | "
               f"{float(r['peak_process_mb']):.0f} | {float(r['peak_heap_mb']):.0f} | {r['check']} |"
               for r in extras]
        md.append("")

    b = [r for r in rows if r["task"] == "1b"]
    if b:
        labels, times, colors = [], [], []
        v1 = [r for r in b if r["variant"] == "v1-unsync"]
        for i, r in enumerate(v1[:3], 1):
            labels.append(f"1: no sync (run {i})")
            times.append(float(r["time_ms"]))
            colors.append(ORANGE)
        for v, name in (("v2-lock", "2: lock"), ("v3-local", "3: per-thread")):
            rs = [float(r["time_ms"]) for r in b if r["variant"] == v]
            if rs:
                labels.append(name)
                times.append(statistics.median(rs))
                colors.append(BLUE if v == "v2-lock" else AQUA)
        fig, ax = plt.subplots(figsize=(6.4, 2.8))
        y = list(range(len(labels)))[::-1]
        ax.barh(y, times, color=colors, height=0.6)
        for yi, t in zip(y, times):
            ax.annotate(f"{t:.0f} ms", (t, yi), xytext=(4, 0), textcoords="offset points",
                        va="center", fontsize=7, color=INK)
        ax.set_yticks(y)
        ax.set_yticklabels(labels)
        ax.set_xlabel("Time (ms)")
        ax.set_xlim(0, max(times) * 1.15)
        ax.grid(axis="y", visible=False)
        ax.set_title(f"Task 1b: three ways to count ({b[0]['threads']} threads)")
        fig.tight_layout()
        fig.savefig(os.path.join(a.out, "task1b_time.png"))
        plt.close(fig)

        md += ["## Task 1b\n",
               "| Version | Run | Time (ms) | Negative | Positive | Zero | Total | Check |",
               "|---|---|---:|---:|---:|---:|---:|---|"]
        md += [f"| {r['variant']} | {r['run']} | {float(r['time_ms']):.0f} | {int(r['negative']):,} | "
               f"{int(r['positive']):,} | {int(r['zero']):,} | {int(r['total']):,} | {r['check']} |" for r in b]
        md.append("")

    m2 = medians(rows, "2", "usb")
    m2l = medians(rows, "2", "local")
    if m2:
        series = [("USB drive", BLUE, m2)] + ([("local disk", ORANGE, m2l)] if m2l else [])
        three_charts("task2", "Task 2", series, "Threads", a.out, 0, 0, ideal=False)
        md += ["## Task 2 (USB)\n", table(m2)]
        if m2l:
            md += ["## Task 2 (same files, local disk)\n", table(m2l)]

    md += ["## Correctness\n",
           "| Task | Variant | Threads | Run | Negative | Positive | Zero | Total | Check |",
           "|---|---|---:|---|---:|---:|---:|---:|---|"]
    md += [f"| {r['task']} | {r['variant']} | {r['threads']} | {r['run']} | {int(r['negative']):,} | "
           f"{int(r['positive']):,} | {int(r['zero']):,} | {int(r['total']):,} | {r['check']} |" for r in rows]

    with open(os.path.join(a.out, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
    print(f"Charts and summary.md written to {a.out}/")


if __name__ == "__main__":
    main()
