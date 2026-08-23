"""Evaluate the promoted farmer TFLite export on the held-out synthetic split."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score, mean_absolute_error, recall_score
from sklearn.model_selection import GroupShuffleSplit

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = PROJECT / "outputs" / "farmer_synthetic"
DEFAULT_CONFIG = PROJECT / "outputs" / "farmer_model_cuda" / "farmer_model_config.json"
DEFAULT_MODEL = PROJECT / "outputs" / "farmer_model_tflite" / "farmer_risk.tflite"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    features = np.load(args.data / "features.npy").astype(np.float32)
    risks = np.load(args.data / "risks.npy").astype(np.float32)
    classes = np.load(args.data / "classes.npy").astype(np.int64)
    groups = np.load(args.data / "groups.npy").astype(np.int64)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    _, holdout = next(GroupShuffleSplit(n_splits=1, test_size=0.20, random_state=config["seed"]).split(features, classes, groups))
    normalized = (features[holdout] - np.asarray(config["feature_mean"], dtype=np.float32)) / np.asarray(config["feature_std"], dtype=np.float32)

    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]
    if tuple(input_details["shape"]) != (1, features.shape[1]):
        raise RuntimeError(f"Unexpected TFLite input shape: {input_details['shape']}")

    predictions = []
    for row in normalized:
        interpreter.set_tensor(input_details["index"], row.reshape(1, -1).astype(np.float32))
        interpreter.invoke()
        predictions.append(interpreter.get_tensor(output_details["index"])[0])
    predictions = np.asarray(predictions, dtype=np.float32)
    predicted_classes = predictions[:, 1:4].argmax(axis=1)
    matrix = confusion_matrix(classes[holdout], predicted_classes, labels=[0, 1, 2]).astype(int).tolist()
    report = {
        "dataset_type": config.get("dataset_type"),
        "warning": config.get("training_warning"),
        "model": str(args.model),
        "input_shape": [int(value) for value in input_details["shape"]],
        "output_shape": [int(value) for value in output_details["shape"]],
        "risk_mae": float(mean_absolute_error(risks[holdout], predictions[:, 0])),
        "class_accuracy": float(accuracy_score(classes[holdout], predicted_classes)),
        "class_macro_f1": float(f1_score(classes[holdout], predicted_classes, average="macro")),
        "urgent_recall": float(recall_score(classes[holdout], predicted_classes, labels=[0, 1, 2], average=None, zero_division=0)[2]),
        "confusion_matrix": matrix,
        "support": int(len(holdout)),
    }
    output = args.output or args.model.with_name("farmer_tflite_evaluation.json")
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
