"""Evaluate the exported TFLite model and render reviewable diagnostics."""

from __future__ import annotations

import csv
import json
import math
import os
from collections import Counter, defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import tensorflow as tf
from PIL import Image
from sklearn.metrics import classification_report, confusion_matrix, f1_score


PROJECT = Path(__file__).resolve().parents[1]
TEST_DIR = PROJECT / "data" / "processed" / "test"
MODEL_PATH = Path(os.environ.get("RIPEN_MODEL_PATH", str(PROJECT / "outputs" / "ripenai.tflite")))
LABEL_PATH = PROJECT / "outputs" / "class_labels.json"
OUT_DIR = PROJECT / "outputs" / os.environ.get("RIPEN_ANALYSIS_DIR", "model_analysis")

IMG_SIZE = (224, 224)
TARGET = 0.85
INK = "#1f2937"
BLUE = "#2563eb"
GREEN = "#16803c"
ORANGE = "#c2410c"
RED = "#b42318"
GRID = "#e5e7eb"


def load_labels() -> list[str]:
    labels = json.loads(LABEL_PATH.read_text(encoding="utf-8"))
    return [labels[str(index)] for index in range(len(labels))]


def split_label(label: str) -> tuple[str, str]:
    fruit, stage = label.rsplit("_", 1)
    return fruit, stage


def source_from_name(path: Path) -> str:
    stem = path.stem.lower()
    for prefix in ("alexcj10", "asadullahprl", "amldvvs", "leftin"):
        if stem.startswith(prefix):
            return prefix
    return stem.split("-", 1)[0].split("_", 1)[0]


def prepare_input(path: Path, detail: dict) -> np.ndarray:
    with Image.open(path) as image:
        image = image.convert("RGB").resize(IMG_SIZE, Image.Resampling.BILINEAR)
        array = np.asarray(image, dtype=np.float32)[None, ...]
    dtype = detail["dtype"]
    if dtype == np.float32:
        return array
    scale, zero = detail.get("quantization", (0.0, 0))
    if not scale:
        return array.astype(dtype)
    return np.round(array / scale + zero).astype(dtype)


def predict(interpreter: tf.lite.Interpreter, input_detail: dict, output_detail: dict, path: Path) -> np.ndarray:
    interpreter.set_tensor(input_detail["index"], prepare_input(path, input_detail))
    interpreter.invoke()
    output = interpreter.get_tensor(output_detail["index"])[0].astype(np.float32)
    if np.any(output < 0) or not np.isclose(float(output.sum()), 1.0, atol=0.03):
        output = np.exp(output - output.max())
        output = output / output.sum()
    return output


def ece(confidences: np.ndarray, correct: np.ndarray, bins: int = 10) -> tuple[float, list[dict]]:
    edges = np.linspace(0.0, 1.0, bins + 1)
    rows = []
    total = len(confidences)
    score = 0.0
    for index in range(bins):
        lower, upper = edges[index], edges[index + 1]
        mask = (confidences >= lower) & ((confidences < upper) if index < bins - 1 else (confidences <= upper))
        count = int(mask.sum())
        accuracy = float(correct[mask].mean()) if count else 0.0
        confidence = float(confidences[mask].mean()) if count else 0.0
        score += (count / total) * abs(accuracy - confidence) if total else 0.0
        rows.append({"lower": float(lower), "upper": float(upper), "count": count, "accuracy": accuracy, "confidence": confidence})
    return float(score), rows


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def style_axes(axes) -> None:
    for axis in np.asarray(axes).flat:
        axis.spines[["top", "right"]].set_visible(False)
        axis.grid(axis="y", color=GRID, linewidth=0.8)
        axis.set_axisbelow(True)
        axis.tick_params(colors=INK)


def plot_confusion(matrix: np.ndarray, labels: list[str], path: Path, title: str) -> None:
    fig, ax = plt.subplots(figsize=(12, 10), constrained_layout=True)
    im = ax.imshow(matrix, cmap="Blues", vmin=0, vmax=1)
    short = [label.replace("_", "\n") for label in labels]
    ax.set_xticks(range(len(labels)), short, rotation=45, ha="right", fontsize=8)
    ax.set_yticks(range(len(labels)), short, fontsize=8)
    ax.set_xlabel("Predicted")
    ax.set_ylabel("True")
    ax.set_title(title, loc="left", fontsize=15, fontweight="bold", color=INK)
    for row in range(matrix.shape[0]):
        for column in range(matrix.shape[1]):
            value = matrix[row, column]
            ax.text(column, row, f"{value:.0%}", ha="center", va="center", fontsize=7,
                    color="white" if value > 0.5 else INK)
    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04, label="Row-normalized rate")
    fig.savefig(path, dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def plot_per_class(report: dict, labels: list[str], path: Path) -> None:
    f1_values = [report[label]["f1-score"] for label in labels]
    recall_values = [report[label]["recall"] for label in labels]
    positions = np.arange(len(labels))
    fig, ax = plt.subplots(figsize=(12, 8), constrained_layout=True)
    ax.barh(positions + 0.18, f1_values, height=0.34, color=BLUE, label="F1")
    ax.barh(positions - 0.18, recall_values, height=0.34, color=GREEN, label="Recall")
    ax.axvline(TARGET, color=ORANGE, linestyle="--", linewidth=1.5, label="85% target")
    ax.set_yticks(positions, [label.replace("_", " ") for label in labels], fontsize=8)
    ax.set_xlim(0, 1.05)
    ax.set_xlabel("Score")
    ax.set_title("TFLite per-class performance", loc="left", fontsize=15, fontweight="bold", color=INK)
    ax.legend(frameon=False, ncol=3, loc="lower right")
    style_axes([ax])
    for index, (f1, recall) in enumerate(zip(f1_values, recall_values)):
        ax.text(min(f1 + 0.02, 1.0), index + 0.18, f"{f1:.0%}", va="center", fontsize=8)
        ax.text(min(recall + 0.02, 1.0), index - 0.18, f"{recall:.0%}", va="center", fontsize=8)
    fig.savefig(path, dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def plot_group_accuracy(group_rows: dict[str, dict], path: Path, title: str, label_key: str) -> None:
    labels = list(group_rows)
    accuracy = [group_rows[label]["accuracy"] for label in labels]
    support = [group_rows[label]["support"] for label in labels]
    colors = [GREEN if value >= TARGET else ORANGE if value >= 0.70 else RED for value in accuracy]
    fig, ax = plt.subplots(figsize=(11, 6), constrained_layout=True)
    bars = ax.bar(labels, accuracy, color=colors)
    ax.axhline(TARGET, color=ORANGE, linestyle="--", linewidth=1.5, label="85% target")
    ax.set_ylim(0, 1.08)
    ax.set_ylabel("Accuracy")
    ax.set_xlabel(label_key)
    ax.set_title(title, loc="left", fontsize=15, fontweight="bold", color=INK)
    ax.legend(frameon=False, loc="lower right")
    style_axes([ax])
    for bar, value, count in zip(bars, accuracy, support):
        ax.text(bar.get_x() + bar.get_width() / 2, value + 0.025, f"{value:.0%}\n(n={count})", ha="center", va="bottom", fontsize=8)
    fig.savefig(path, dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def plot_calibration(rows: list[dict], path: Path) -> None:
    nonempty = [row for row in rows if row["count"]]
    confidence = [row["confidence"] for row in nonempty]
    accuracy = [row["accuracy"] for row in nonempty]
    counts = [row["count"] for row in nonempty]
    fig, ax = plt.subplots(figsize=(8, 7), constrained_layout=True)
    ax.plot([0, 1], [0, 1], linestyle="--", color="#6b7280", label="Perfect calibration")
    scatter = ax.scatter(confidence, accuracy, s=np.array(counts) * 2.5 + 25, color=BLUE, alpha=0.85)
    for row in nonempty:
        ax.annotate(str(row["count"]), (row["confidence"], row["accuracy"]), xytext=(4, 4), textcoords="offset points", fontsize=8)
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.set_xlabel("Mean predicted confidence")
    ax.set_ylabel("Observed accuracy")
    ax.set_title("Confidence calibration (TFLite)", loc="left", fontsize=15, fontweight="bold", color=INK)
    ax.legend(frameon=False, loc="upper left")
    style_axes([ax])
    fig.savefig(path, dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def plot_source_accuracy(source_rows: dict[str, dict], path: Path) -> None:
    labels = list(source_rows)
    accuracy = [source_rows[label]["accuracy"] for label in labels]
    joint = [source_rows[label]["joint_accuracy"] for label in labels]
    positions = np.arange(len(labels))
    fig, ax = plt.subplots(figsize=(10, 6), constrained_layout=True)
    ax.bar(positions - 0.18, accuracy, width=0.36, color=BLUE, label="Stage accuracy")
    ax.bar(positions + 0.18, joint, width=0.36, color=GREEN, label="Fruit + stage")
    ax.axhline(TARGET, color=ORANGE, linestyle="--", linewidth=1.5, label="85% target")
    ax.set_xticks(positions, labels)
    ax.set_ylim(0, 1.08)
    ax.set_ylabel("Accuracy")
    ax.set_xlabel("Dataset filename prefix")
    ax.set_title("Generalization by dataset source", loc="left", fontsize=15, fontweight="bold", color=INK)
    ax.legend(frameon=False, ncol=3, loc="lower right")
    style_axes([ax])
    fig.savefig(path, dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def plot_error_gallery(rows: list[dict], path: Path) -> None:
    errors = [row for row in rows if not row["correct"]]
    errors.sort(key=lambda row: row["confidence"], reverse=True)
    errors = errors[:24]
    columns = 4
    figure, axes = plt.subplots(math.ceil(max(len(errors), 1) / columns), columns, figsize=(14, 3.4 * math.ceil(max(len(errors), 1) / columns)))
    axes = np.asarray(axes).reshape(-1)
    for axis in axes:
        axis.axis("off")
    for axis, row in zip(axes, errors):
        with Image.open(row["path"]) as image:
            axis.imshow(image.convert("RGB"))
        axis.set_title(f"true {row['true_label'].replace('_', ' ')}\npred {row['pred_label'].replace('_', ' ')} ({row['confidence']:.0%})", fontsize=8)
        axis.axis("off")
    figure.suptitle("Highest-confidence TFLite errors", fontsize=16, fontweight="bold", color=INK)
    figure.tight_layout(rect=[0, 0, 1, 0.96])
    figure.savefig(path, dpi=160, bbox_inches="tight", facecolor="white")
    plt.close(figure)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    class_names = load_labels()
    class_to_index = {name: index for index, name in enumerate(class_names)}
    image_paths = sorted(TEST_DIR.glob("*/*.jpg"))
    if not image_paths:
        raise RuntimeError(f"No test images found in {TEST_DIR}")

    interpreter = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    rows: list[dict] = []
    y_true: list[int] = []
    y_pred: list[int] = []
    confidences: list[float] = []
    top2_confidences: list[float] = []
    for index, path in enumerate(image_paths, start=1):
        true_label = path.parent.name
        probabilities = predict(interpreter, input_detail, output_detail, path)
        order = np.argsort(probabilities)[::-1]
        predicted = int(order[0])
        true_index = class_to_index[true_label]
        row = {
            "path": str(path.relative_to(PROJECT)),
            "true_label": true_label,
            "pred_label": class_names[predicted],
            "true_index": true_index,
            "pred_index": predicted,
            "correct": bool(predicted == true_index),
            "confidence": float(probabilities[predicted]),
            "top2_label": class_names[int(order[1])],
            "top2_confidence": float(probabilities[order[1]]),
            "source": source_from_name(path),
        }
        rows.append(row)
        y_true.append(true_index)
        y_pred.append(predicted)
        confidences.append(row["confidence"])
        top2_confidences.append(row["top2_confidence"])
        if index % 250 == 0 or index == len(image_paths):
            print(f"evaluated {index}/{len(image_paths)}")

    y_true_array = np.asarray(y_true)
    y_pred_array = np.asarray(y_pred)
    confidence_array = np.asarray(confidences)
    correct_array = y_true_array == y_pred_array
    report = classification_report(y_true_array, y_pred_array, labels=range(len(class_names)), target_names=class_names, output_dict=True, zero_division=0)
    cm = confusion_matrix(y_true_array, y_pred_array, labels=range(len(class_names)), normalize="true")
    calibration_error, calibration_rows = ece(confidence_array, correct_array)

    fruit_names = sorted({split_label(label)[0] for label in class_names})
    preferred_stage_order = ["unripe", "nearly_ripe", "ripe", "overripe", "rotten"]
    observed_stages = {split_label(label)[1] for label in class_names}
    stage_names = [stage for stage in preferred_stage_order if stage in observed_stages]
    stage_names.extend(sorted(observed_stages - set(stage_names)))
    fruit_rows: dict[str, dict] = {}
    stage_rows: dict[str, dict] = {}
    for fruit in fruit_names:
        mask = np.array([split_label(class_names[index])[0] == fruit for index in y_true_array])
        fruit_rows[fruit] = {"accuracy": float(correct_array[mask].mean()), "support": int(mask.sum())}
    for stage in stage_names:
        mask = np.array([split_label(class_names[index])[1] == stage for index in y_true_array])
        stage_rows[stage] = {"accuracy": float(correct_array[mask].mean()), "support": int(mask.sum())}

    source_rows: dict[str, dict] = {}
    for source in sorted({row["source"] for row in rows}):
        source_mask = np.array([row["source"] == source for row in rows])
        stage_correct = []
        joint_correct = []
        for row in np.asarray(rows, dtype=object)[source_mask]:
            true_fruit, true_stage = split_label(row["true_label"])
            pred_fruit, pred_stage = split_label(row["pred_label"])
            stage_correct.append(true_stage == pred_stage)
            joint_correct.append(true_fruit == pred_fruit and true_stage == pred_stage)
        source_rows[source] = {
            "accuracy": float(np.mean(stage_correct)),
            "joint_accuracy": float(np.mean(joint_correct)),
            "support": int(source_mask.sum()),
        }

    true_fruits = [split_label(class_names[index])[0] for index in y_true_array]
    pred_fruits = [split_label(class_names[index])[0] for index in y_pred_array]
    true_stages = [split_label(class_names[index])[1] for index in y_true_array]
    pred_stages = [split_label(class_names[index])[1] for index in y_pred_array]
    fruit_cm = confusion_matrix(true_fruits, pred_fruits, labels=fruit_names, normalize="true")
    stage_cm = confusion_matrix(true_stages, pred_stages, labels=stage_names, normalize="true")
    fruit_correct = np.asarray([true == pred for true, pred in zip(true_fruits, pred_fruits)])
    stage_correct = np.asarray([true == pred for true, pred in zip(true_stages, pred_stages)])
    joint_correct = fruit_correct & stage_correct

    metrics = {
        "model": str(MODEL_PATH.relative_to(PROJECT)),
        "input": {"shape": input_detail["shape"].tolist(), "dtype": str(input_detail["dtype"]), "preprocess": "RGB resize 224x224; raw 0..255 because MobileNetV2 preprocess is embedded in the model"},
        "test_images": len(rows),
        "classes": class_names,
        "overall": {
            "accuracy": float(correct_array.mean()),
            "macro_f1": float(f1_score(y_true_array, y_pred_array, average="macro")),
            "weighted_f1": float(f1_score(y_true_array, y_pred_array, average="weighted")),
            "fruit_accuracy": float(fruit_correct.mean()),
            "stage_accuracy": float(stage_correct.mean()),
            "joint_fruit_stage_accuracy": float(joint_correct.mean()),
            "mean_top1_confidence": float(confidence_array.mean()),
            "mean_top2_confidence": float(np.mean(top2_confidences)),
            "error_calibration_ece": calibration_error,
            "target_accuracy": TARGET,
            "target_reached": bool(correct_array.mean() >= TARGET),
        },
        "per_fruit": fruit_rows,
        "per_stage": stage_rows,
        "by_source": source_rows,
        "classification_report": report,
        "calibration": calibration_rows,
        "confusion_matrix": cm.tolist(),
        "fruit_confusion_matrix": {"labels": fruit_names, "matrix": fruit_cm.tolist()},
        "stage_confusion_matrix": {"labels": stage_names, "matrix": stage_cm.tolist()},
        "error_counts": {
            "total": int((~correct_array).sum()),
            "fruit_wrong": int((~fruit_correct).sum()),
            "stage_wrong": int((~stage_correct).sum()),
            "fruit_right_stage_wrong": int((fruit_correct & ~stage_correct).sum()),
            "fruit_wrong_stage_right": int((~fruit_correct & stage_correct).sum()),
        },
    }

    (OUT_DIR / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    write_csv(OUT_DIR / "predictions.csv", rows)
    write_csv(OUT_DIR / "classification_report.csv", [{"label": label, **report[label]} for label in class_names])
    plot_confusion(cm, class_names, OUT_DIR / "confusion_matrix.png", "TFLite normalized confusion matrix")
    plot_per_class(report, class_names, OUT_DIR / "per_class_metrics.png")
    plot_group_accuracy(fruit_rows, OUT_DIR / "fruit_accuracy.png", "Accuracy by fruit", "Fruit")
    plot_group_accuracy(stage_rows, OUT_DIR / "stage_accuracy.png", "Accuracy by ripeness stage", "Stage")
    plot_calibration(calibration_rows, OUT_DIR / "confidence_calibration.png")
    plot_source_accuracy(source_rows, OUT_DIR / "source_accuracy.png")
    plot_error_gallery(rows, OUT_DIR / "error_gallery.png")

    figure, axes = plt.subplots(2, 2, figsize=(14, 10), constrained_layout=True)
    axes = np.asarray(axes)
    axes[0, 0].bar(["Overall", "Fruit", "Stage", "Joint"], [metrics["overall"][key] for key in ["accuracy", "fruit_accuracy", "stage_accuracy", "joint_fruit_stage_accuracy"]], color=[BLUE, GREEN, ORANGE, RED])
    axes[0, 0].axhline(TARGET, color=INK, linestyle="--", linewidth=1.2)
    axes[0, 0].set_ylim(0, 1.05)
    axes[0, 0].set_title("Accuracy summary", loc="left", fontweight="bold")
    axes[0, 0].set_ylabel("Accuracy")
    axes[0, 1].bar(["Macro F1", "Weighted F1"], [metrics["overall"]["macro_f1"], metrics["overall"]["weighted_f1"]], color=[BLUE, GREEN])
    axes[0, 1].set_ylim(0, 1.05)
    axes[0, 1].set_title("F1 summary", loc="left", fontweight="bold")
    axes[0, 1].set_ylabel("F1")
    axes[1, 0].bar(stage_names, [stage_rows[name]["accuracy"] for name in stage_names], color=[RED, GREEN, ORANGE])
    axes[1, 0].set_ylim(0, 1.05)
    axes[1, 0].set_title("Stage accuracy", loc="left", fontweight="bold")
    axes[1, 0].set_ylabel("Accuracy")
    axes[1, 1].bar(["Fruit wrong", "Stage wrong", "Joint wrong"], [metrics["error_counts"]["fruit_wrong"], metrics["error_counts"]["stage_wrong"], int((~joint_correct).sum())], color=[RED, ORANGE, BLUE])
    axes[1, 1].set_title("Error composition", loc="left", fontweight="bold")
    axes[1, 1].set_ylabel("Images")
    style_axes(axes)
    figure.suptitle("RipenAI TFLite Model Analysis", fontsize=17, fontweight="bold", color=INK)
    figure.savefig(OUT_DIR / "model_analysis_report.png", dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(figure)

    summary = {
        "accuracy": metrics["overall"]["accuracy"],
        "macro_f1": metrics["overall"]["macro_f1"],
        "weighted_f1": metrics["overall"]["weighted_f1"],
        "fruit_accuracy": metrics["overall"]["fruit_accuracy"],
        "stage_accuracy": metrics["overall"]["stage_accuracy"],
        "joint_accuracy": metrics["overall"]["joint_fruit_stage_accuracy"],
        "test_images": metrics["test_images"],
        "target_reached": metrics["overall"]["target_reached"],
        "artifacts": sorted(path.name for path in OUT_DIR.iterdir() if path.is_file()),
    }
    (OUT_DIR / "README.md").write_text(
        "# RipenAI TFLite model analysis\n\n"
        f"Evaluated **{summary['test_images']}** held-out test images using the exported Android TFLite model.\n\n"
        f"- Accuracy: **{summary['accuracy']:.2%}**\n"
        f"- Macro F1: **{summary['macro_f1']:.2%}**\n"
        f"- Weighted F1: **{summary['weighted_f1']:.2%}**\n"
        f"- Fruit identity accuracy: **{summary['fruit_accuracy']:.2%}**\n"
        f"- Ripeness stage accuracy: **{summary['stage_accuracy']:.2%}**\n"
        f"- Exact fruit + stage accuracy: **{summary['joint_accuracy']:.2%}**\n\n"
        "See `metrics.json` and `predictions.csv` for the full audit trail. PNG files are static review charts.\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
