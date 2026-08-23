"""Render a compact, reviewable report from the Worker stress-test output."""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np


ROOT = Path(__file__).resolve().parents[2]
INPUT = ROOT / "outputs" / "worker_analysis" / "worker_stress_results.json"
OUTPUT = ROOT / "outputs" / "worker_analysis" / "worker_stress_report.png"

GREEN = "#16803c"
BLUE = "#2563eb"
ORANGE = "#c2410c"
RED = "#b42318"
INK = "#1f2937"
GRID = "#e5e7eb"


def main() -> None:
    payload = json.loads(INPUT.read_text(encoding="utf-8"))
    results = payload["results"]
    valid = [item for item in results if item.get("group") == "valid"]
    valid_success = sum(item.get("status") == 200 and item.get("ok") is True for item in valid)
    valid_failure = len(valid) - valid_success
    provider_counts: dict[str, int] = {}
    latencies: list[float] = []
    status_counts: dict[str, int] = {}
    for item in results:
        status = str(item.get("status", "error"))
        status_counts[status] = status_counts.get(status, 0) + 1
        if item.get("group") == "valid":
            provider = item.get("provider") or "failed"
            provider_counts[provider] = provider_counts.get(provider, 0) + 1
        if item.get("group") == "valid" and isinstance(item.get("latency_ms"), (int, float)):
            latencies.append(float(item["latency_ms"]))

    latencies.sort()
    percentile = lambda fraction: latencies[min(len(latencies) - 1, int(len(latencies) * fraction))] if latencies else 0
    p50 = float(percentile(0.5))
    p95 = float(percentile(0.95))

    fig, axes = plt.subplots(2, 2, figsize=(13, 8), constrained_layout=True)
    fig.patch.set_facecolor("white")
    fig.suptitle("RipenAI Question Worker — Stress Test", fontsize=17, fontweight="bold", color=INK)

    ax = axes[0, 0]
    labels = ["Valid\ncontract pass", "Valid\nfailed"]
    values = [valid_success, valid_failure]
    bars = ax.bar(labels, values, color=[GREEN, RED], width=0.58)
    ax.set_title("Valid request reliability", loc="left", fontweight="bold", color=INK)
    ax.set_ylabel("Requests")
    ax.set_ylim(0, max(values + [1]) * 1.2)
    for bar, value in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width() / 2, value + 0.2, str(value), ha="center", fontweight="bold")

    ax = axes[0, 1]
    provider_order = ["groq", "cloudflare", "failed"]
    providers = [name for name in provider_order if name in provider_counts]
    counts = [provider_counts[name] for name in providers]
    colors = [BLUE if name == "groq" else ORANGE if name == "cloudflare" else RED for name in providers]
    bars = ax.bar(providers, counts, color=colors, width=0.58)
    ax.set_title("Provider routing", loc="left", fontweight="bold", color=INK)
    ax.set_ylabel("Requests")
    ax.set_ylim(0, max(counts + [1]) * 1.2)
    for bar, value in zip(bars, counts):
        ax.text(bar.get_x() + bar.get_width() / 2, value + 0.2, str(value), ha="center", fontweight="bold")

    ax = axes[1, 0]
    if latencies:
        x = np.arange(1, len(latencies) + 1)
        ax.plot(x, latencies, color=BLUE, linewidth=2, marker=".", markersize=5)
        ax.axhline(p50, color=GREEN, linestyle="--", linewidth=1.5, label=f"P50 {p50:.0f} ms")
        ax.axhline(p95, color=ORANGE, linestyle="--", linewidth=1.5, label=f"P95 {p95:.0f} ms")
        ax.legend(frameon=False, loc="upper left")
    ax.set_title("End-to-end latency", loc="left", fontweight="bold", color=INK)
    ax.set_xlabel("Request rank (sorted)")
    ax.set_ylabel("Milliseconds")

    ax = axes[1, 1]
    statuses = sorted(status_counts, key=lambda value: (value == "200", value))
    counts = [status_counts[value] for value in statuses]
    colors = [GREEN if value == "200" else RED if value.startswith("4") or value.startswith("5") else BLUE for value in statuses]
    bars = ax.bar(statuses, counts, color=colors, width=0.58)
    ax.set_title("HTTP protocol outcomes", loc="left", fontweight="bold", color=INK)
    ax.set_xlabel("HTTP status")
    ax.set_ylabel("Requests")
    ax.set_ylim(0, max(counts + [1]) * 1.2)
    for bar, value in zip(bars, counts):
        ax.text(bar.get_x() + bar.get_width() / 2, value + 0.2, str(value), ha="center", fontweight="bold")

    for ax in axes.flat:
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(axis="y", color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        ax.tick_params(colors=INK)
        ax.title.set_color(INK)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT, dpi=180, bbox_inches="tight")
    plt.close(fig)
    print(f"saved {OUTPUT}")


if __name__ == "__main__":
    main()
