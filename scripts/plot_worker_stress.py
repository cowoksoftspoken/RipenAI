"""Create a reviewable static report from question-worker stress results."""

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[1]
ANALYSIS_DIR = ROOT / "outputs" / "worker_analysis"


def main() -> None:
    data = json.loads((ANALYSIS_DIR / "worker_stress_results.json").read_text(encoding="utf-8"))
    summary = data["summary"]
    results = data["results"]
    valid = [item for item in results if item.get("group") == "valid"]
    latencies = sorted(item["latency_ms"] for item in valid if item.get("latency_ms") is not None)

    green = "#16803c"
    blue = "#2563eb"
    orange = "#c2410c"
    ink = "#1f2937"
    grid = "#e5e7eb"

    plt.rcParams.update({"font.family": "DejaVu Sans", "axes.titlesize": 12, "axes.labelsize": 10})
    fig, axes = plt.subplots(2, 2, figsize=(14, 9), facecolor="white")
    fig.suptitle("RipenAI Question Worker — Stress Test", fontsize=17, fontweight="bold", color=ink)

    ax = axes[0, 0]
    success = summary["valid_successes"]
    failure = summary["valid_cases"] - success
    bars = ax.bar(["Berhasil", "Gagal"], [success, failure], color=[green, orange], width=0.55)
    ax.set_title("Valid request contract")
    ax.set_ylabel("Jumlah request")
    ax.set_ylim(0, max(1, summary["valid_cases"]) * 1.15)
    ax.grid(axis="y", color=grid)
    ax.set_axisbelow(True)
    for bar in bars:
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.4, f"{int(bar.get_height())}", ha="center", color=ink)

    ax = axes[0, 1]
    providers = summary["providers"]
    provider_names = list(providers)
    provider_values = [providers[name] for name in provider_names]
    colors = [blue if name == "groq" else green if name == "cloudflare" else orange for name in provider_names]
    bars = ax.bar(provider_names, provider_values, color=colors, width=0.55)
    ax.set_title("Provider yang melayani request")
    ax.set_ylabel("Jumlah request valid")
    ax.grid(axis="y", color=grid)
    ax.set_axisbelow(True)
    for bar in bars:
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3, f"{int(bar.get_height())}", ha="center", color=ink)

    ax = axes[1, 0]
    ax.plot(range(1, len(latencies) + 1), latencies, color=blue, marker="o", markersize=3, linewidth=1.5)
    ax.axhline(summary["latency_ms"]["p50"], color=green, linestyle="--", linewidth=1.5, label=f"P50 {summary['latency_ms']['p50']} ms")
    ax.axhline(summary["latency_ms"]["p95"], color=orange, linestyle="--", linewidth=1.5, label=f"P95 {summary['latency_ms']['p95']} ms")
    ax.set_title("Latency request valid (diurutkan)")
    ax.set_xlabel("Request ke-")
    ax.set_ylabel("Milidetik")
    ax.grid(color=grid)
    ax.set_axisbelow(True)
    ax.legend(frameon=False)

    ax = axes[1, 1]
    statuses = {}
    for item in results:
        status = str(item.get("status"))
        statuses[status] = statuses.get(status, 0) + 1
    status_names = list(statuses)
    status_values = [statuses[name] for name in status_names]
    bars = ax.bar(status_names, status_values, color=[green if name == "200" else blue if name == "204" else orange for name in status_names], width=0.55)
    ax.set_title("HTTP status seluruh skenario")
    ax.set_ylabel("Jumlah request")
    ax.grid(axis="y", color=grid)
    ax.set_axisbelow(True)
    for bar in bars:
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3, f"{int(bar.get_height())}", ha="center", color=ink)

    for ax in axes.flat:
        ax.spines[["top", "right"]].set_visible(False)
        ax.tick_params(colors=ink)
        ax.title.set_color(ink)

    fig.text(
        0.01,
        0.01,
        f"Valid: {summary['valid_successes']}/{summary['valid_cases']} · Protocol: {summary['invalid_protocol_successes']}/{len(results) - len(valid)} · Mean: {summary['latency_ms']['mean']} ms · P95: {summary['latency_ms']['p95']} ms",
        color=ink,
        fontsize=10,
    )
    fig.tight_layout(rect=[0, 0.04, 1, 0.95])
    output = ANALYSIS_DIR / "worker_stress_report.png"
    fig.savefig(output, dpi=160, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print(output)


if __name__ == "__main__":
    main()
