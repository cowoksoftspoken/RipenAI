"""Evaluate the Farmer ML V1 TFLite export on standard and OOD synthetic splits."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score, mean_absolute_error, recall_score

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = PROJECT / "outputs" / "farmer_synthetic_v1"
DEFAULT_CONFIG = PROJECT / "outputs" / "farmer_model_v1_cuda" / "farmer_model_config.json"
DEFAULT_MODEL = PROJECT / "outputs" / "farmer_model_v1_tflite" / "farmer_risk.tflite"


def calculate_report(target_risk: np.ndarray, target_hours: np.ndarray, target_classes: np.ndarray, outputs: np.ndarray) -> dict:
    predicted_classes = outputs[:, 2:5].argmax(axis=1)
    return {
        "risk_mae": float(mean_absolute_error(target_risk, outputs[:, 0])),
        "hours_to_action_mae": float(mean_absolute_error(target_hours, outputs[:, 1])),
        "class_accuracy": float(accuracy_score(target_classes, predicted_classes)),
        "class_macro_f1": float(f1_score(target_classes, predicted_classes, average="macro")),
        "urgent_recall": float(recall_score(target_classes, predicted_classes, labels=[0, 1, 2], average=None, zero_division=0)[2]),
        "confusion_matrix": confusion_matrix(target_classes, predicted_classes, labels=[0, 1, 2]).astype(int).tolist(),
        "support": int(len(target_classes)),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    windows = np.load(args.data / "sensor_windows.npy").astype(np.float32)
    fruit = np.load(args.data / "fruit_one_hot.npy").astype(np.float32)
    risks = np.load(args.data / "risks.npy").astype(np.float32)
    hours = np.load(args.data / "hours_to_action.npy").astype(np.float32)
    classes = np.load(args.data / "classes.npy").astype(np.int64)
    split = np.load(args.data / "split.npy").astype(np.int64)
    scenarios = np.asarray(json.loads((args.data / "scenarios.json").read_text(encoding="utf-8")))
    mean = np.asarray(config["feature_mean"], dtype=np.float32)
    std = np.asarray(config["feature_std"], dtype=np.float32)
    # V1 metadata exposes the complete feature contract for Android. The
    # sensor windows still need only the first three channel statistics;
    # fruit one-hot entries are already in their original 0/1 scale.
    sensor_channels = windows.shape[2]
    if mean.size == sensor_channels:
        sensor_mean, sensor_std = mean, std
    elif mean.size >= sensor_channels:
        sensor_mean, sensor_std = mean[:sensor_channels], std[:sensor_channels]
    else:
        raise RuntimeError(f"Metadata statistics are too short: {mean.size} for {sensor_channels} sensor channels")
    normalized = (windows - sensor_mean.reshape(1, 1, -1)) / np.maximum(sensor_std.reshape(1, 1, -1), 1e-6)
    features = np.concatenate([normalized.reshape(len(windows), -1), fruit], axis=1).astype(np.float32)

    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]
    expected_dim = int(config["feature_dim"])
    if tuple(input_details["shape"]) != (1, expected_dim) or tuple(output_details["shape"]) != (1, 5):
        raise RuntimeError(f"Unexpected TFLite shapes: input={input_details['shape']} output={output_details['shape']}")

    outputs = []
    for row in features:
        interpreter.set_tensor(input_details["index"], row.reshape(1, -1))
        interpreter.invoke()
        outputs.append(interpreter.get_tensor(output_details["index"])[0])
    outputs = np.asarray(outputs, dtype=np.float32)

    reports = {}
    for split_id, name in ((2, "holdout"), (3, "ood")):
        mask = split == split_id
        report = calculate_report(risks[mask], hours[mask], classes[mask], outputs[mask])
        report["per_scenario"] = {}
        for scenario in sorted(set(scenarios[mask])):
            scenario_mask = mask & (scenarios == scenario)
            report["per_scenario"][scenario] = calculate_report(risks[scenario_mask], hours[scenario_mask], classes[scenario_mask], outputs[scenario_mask])
        reports[name] = report

    report = {
        "dataset_type": config.get("dataset_type"),
        "warning": config.get("training_warning"),
        "model": args.model.name,
        "input_shape": [int(value) for value in input_details["shape"]],
        "output_shape": [int(value) for value in output_details["shape"]],
        **reports,
    }
    output = args.output or args.model.with_name("farmer_tflite_evaluation.json")
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
