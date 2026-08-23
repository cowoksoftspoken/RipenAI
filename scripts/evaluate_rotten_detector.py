"""Evaluate the binary rotten safety detector on the held-out processed set."""

from __future__ import annotations

import csv
import json
import os
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import tensorflow as tf
from PIL import Image
from sklearn.metrics import confusion_matrix, precision_recall_fscore_support

PROJECT = Path(__file__).resolve().parents[1]
MODEL = Path(os.environ.get("RIPEN_ROTTEN_MODEL_PATH", PROJECT / "outputs" / "rotten_detector_cuda.tflite"))
INPUT = Path(os.environ.get("RIPEN_ROTTEN_TEST_DIR", PROJECT / "data" / "processed" / "test"))
OUTPUT = Path(os.environ.get("RIPEN_ROTTEN_ANALYSIS_DIR", PROJECT / "outputs" / "rotten_detector_analysis"))


def predict(interpreter: tf.lite.Interpreter, input_detail: dict, output_detail: dict, path: Path) -> float:
    image = Image.open(path).convert("RGB").resize((224, 224), Image.Resampling.BILINEAR)
    array = np.asarray(image, dtype=np.float32)[None, ...]
    interpreter.set_tensor(input_detail["index"], array)
    interpreter.invoke()
    values = interpreter.get_tensor(output_detail["index"])[0]
    values = values - values.max()
    probabilities = np.exp(values) / np.exp(values).sum()
    return float(probabilities[1])


def main() -> None:
    interpreter = tf.lite.Interpreter(model_path=str(MODEL))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    rows = []
    for path in sorted(INPUT.rglob("*.jpg")):
        true_rotten = int(path.parent.name.rsplit("_", 1)[-1] == "rotten")
        score = predict(interpreter, input_detail, output_detail, path)
        rows.append({"path": str(path), "true_rotten": true_rotten, "rotten_probability": score})
    y_true = np.asarray([row["true_rotten"] for row in rows])
    scores = np.asarray([row["rotten_probability"] for row in rows])
    threshold_rows = []
    for threshold in np.arange(0.50, 0.951, 0.025):
        y_pred = (scores >= threshold).astype(int)
        precision, recall, f1, _ = precision_recall_fscore_support(y_true, y_pred, average="binary", zero_division=0)
        threshold_rows.append({"threshold": float(threshold), "precision": float(precision), "recall": float(recall), "f1": float(f1), "false_positive": int(((y_pred == 1) & (y_true == 0)).sum())})
    selected = max(threshold_rows, key=lambda row: (row["precision"] >= 0.90, row["recall"], row["f1"]))
    threshold = selected["threshold"]
    y_pred = (scores >= threshold).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(y_true, y_pred, average="binary", zero_division=0)
    matrix = confusion_matrix(y_true, y_pred, labels=[0, 1])
    metrics = {
        "model": str(MODEL), "dataset": str(INPUT), "support": len(rows),
        "positive_support_rotten": int(y_true.sum()), "negative_support": int((y_true == 0).sum()),
        "threshold": threshold, "accuracy": float((y_true == y_pred).mean()),
        "precision_rotten": float(precision), "recall_rotten": float(recall), "f1_rotten": float(f1),
        "false_positive": int(((y_pred == 1) & (y_true == 0)).sum()), "confusion_matrix": matrix.tolist(),
        "threshold_sweep": threshold_rows,
    }
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    with (OUTPUT / "predictions.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    fig, axis = plt.subplots(figsize=(5.5, 4.5))
    image = axis.imshow(matrix, cmap="Oranges")
    axis.set_title("Rotten detector confusion")
    axis.set_xlabel("Predicted")
    axis.set_ylabel("True")
    axis.set_xticks([0, 1], ["not rotten", "rotten"], rotation=20, ha="right")
    axis.set_yticks([0, 1], ["not rotten", "rotten"])
    for row in range(2):
        for column in range(2):
            axis.text(column, row, str(matrix[row, column]), ha="center", va="center")
    fig.colorbar(image, ax=axis, fraction=0.046, pad=0.04, label="Images")
    fig.tight_layout()
    fig.savefig(OUTPUT / "confusion_matrix.png", dpi=160)
    plt.close(fig)
    figure, axis = plt.subplots(figsize=(6.5, 4.5))
    axis.plot([row["threshold"] for row in threshold_rows], [row["precision"] for row in threshold_rows], label="Precision")
    axis.plot([row["threshold"] for row in threshold_rows], [row["recall"] for row in threshold_rows], label="Recall")
    axis.plot([row["threshold"] for row in threshold_rows], [row["f1"] for row in threshold_rows], label="F1")
    axis.axvline(threshold, color="black", linestyle="--", label=f"selected {threshold:.3f}")
    axis.set_ylim(0, 1.05)
    axis.set_xlabel("Rotten probability threshold")
    axis.set_ylabel("Score")
    axis.set_title("Rotten detector threshold selection")
    axis.legend()
    figure.tight_layout()
    figure.savefig(OUTPUT / "threshold_sweep.png", dpi=160)
    plt.close(figure)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
