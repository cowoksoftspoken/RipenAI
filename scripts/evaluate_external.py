"""Evaluate the exported TFLite model on an external folder-labelled set."""

from __future__ import annotations

import csv
import json
import os
from collections import Counter
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import tensorflow as tf
from PIL import Image


PROJECT = Path(__file__).resolve().parents[1]
MODEL = Path(os.environ.get("RIPEN_MODEL_PATH", PROJECT / "outputs" / "ripenai.tflite"))
INPUT = Path(os.environ.get("RIPEN_EXTERNAL_DIR", PROJECT / "data" / "raw_external" / "bananaimagebd" / "extracted"))
OUTPUT = Path(os.environ.get("RIPEN_EXTERNAL_ANALYSIS_DIR", PROJECT / "outputs" / "external_analysis"))
STAGES = ("unripe", "ripe", "overripe")


def load_labels() -> list[str]:
    data = json.loads((PROJECT / "outputs" / "class_labels.json").read_text(encoding="utf-8"))
    return [data[str(index)] for index in range(len(data))]


def predict(interpreter: tf.lite.Interpreter, input_detail: dict, output_detail: dict, image_path: Path) -> np.ndarray:
    image = Image.open(image_path).convert("RGB").resize((224, 224), Image.Resampling.BILINEAR)
    array = np.asarray(image, dtype=np.float32)[None, ...]
    interpreter.set_tensor(input_detail["index"], array)
    interpreter.invoke()
    values = interpreter.get_tensor(output_detail["index"])[0]
    if np.all(values >= 0) and 0.98 <= float(values.sum()) <= 1.02:
        return values
    values = values - values.max()
    probabilities = np.exp(values)
    return probabilities / probabilities.sum()


def main() -> None:
    labels = load_labels()
    interpreter = tf.lite.Interpreter(model_path=str(MODEL))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    rows: list[dict] = []
    for image_path in sorted(INPUT.rglob("*.jpg")):
        true_stage = image_path.parent.name.removeprefix("banana_")
        if true_stage not in {"unripe", "ripe", "overripe"}:
            continue
        values = predict(interpreter, input_detail, output_detail, image_path)
        banana_indices = [index for index, label in enumerate(labels) if label.startswith("banana_")]
        banana_mass = float(values[banana_indices].sum())
        conditional = values / max(banana_mass, 1e-6)
        banana_stage_indices = {
            label.rsplit("_", 1)[1]: index
            for index, label in enumerate(labels)
            if label.startswith("banana_")
        }
        stage = max(STAGES, key=lambda candidate: float(conditional[banana_stage_indices[candidate]]))
        global_index = int(values.argmax())
        rows.append({
            "path": str(image_path),
            "true_stage": true_stage,
            "pred_stage_when_banana_selected": stage,
            "global_pred_label": labels[global_index],
            "banana_support": banana_mass,
            "confidence": float(conditional[banana_stage_indices[stage]]),
            "correct_stage": stage == true_stage,
        })

    correct = [row["correct_stage"] for row in rows]
    stage_counts = Counter(row["true_stage"] for row in rows)
    stage_accuracy = {
        stage: {
            "accuracy": sum(row["correct_stage"] for row in rows if row["true_stage"] == stage) / max(stage_counts[stage], 1),
            "support": stage_counts[stage],
        }
        for stage in STAGES
    }
    global_fruit_errors = sum(not row["global_pred_label"].startswith("banana_") for row in rows)
    metrics = {
        "model": str(MODEL),
        "dataset": str(INPUT),
        "support": len(rows),
        "banana_stage_accuracy_after_fruit_selection": sum(correct) / max(len(correct), 1),
        "global_non_banana_predictions": global_fruit_errors,
        "per_stage": stage_accuracy,
        "confusion": {true: {pred: sum(row["true_stage"] == true and row["pred_stage_when_banana_selected"] == pred for row in rows) for pred in STAGES} for true in STAGES},
    }
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    with (OUTPUT / "predictions.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]) if rows else ["path"])
        writer.writeheader()
        writer.writerows(rows)

    matrix = np.array([[metrics["confusion"][true][pred] for pred in STAGES] for true in STAGES])
    fig, axis = plt.subplots(figsize=(6.5, 5.2))
    image = axis.imshow(matrix, cmap="Greens")
    axis.set_title("BananaImageBD external stage confusion")
    axis.set_xlabel("Predicted stage after banana selection")
    axis.set_ylabel("True stage")
    axis.set_xticks(range(len(STAGES)), STAGES, rotation=25, ha="right")
    axis.set_yticks(range(len(STAGES)), STAGES)
    for row_index in range(len(STAGES)):
        for column_index in range(len(STAGES)):
            axis.text(column_index, row_index, str(matrix[row_index, column_index]), ha="center", va="center")
    fig.colorbar(image, ax=axis, fraction=0.046, pad=0.04, label="Images")
    fig.tight_layout()
    fig.savefig(OUTPUT / "confusion_matrix.png", dpi=160)
    plt.close(fig)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
