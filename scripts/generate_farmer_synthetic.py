"""Generate deterministic synthetic DHT22 + MQ-3 windows for farmer-model experiments.

This is a development dataset only. It is deliberately documented as synthetic
and must never be presented as field accuracy. The generator creates separate
trajectory groups so train/validation/test splitting can be group-aware.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = PROJECT / "outputs" / "farmer_synthetic"
SEED = 20260823
WINDOW_SIZE = 16
FRUITS = ["apple", "banana", "mango", "orange", "papaya", "pineapple", "tomato", "avocado", "durian"]
STAGES = ["safe", "attention", "urgent"]

# Small offsets make the synthetic set less trivial without pretending these are
# measured fruit constants. The actual fruit-specific bands remain configurable
# in the Android asset and must be calibrated with real logs later.
FRUIT_PROFILES = {
    "apple": (32.0, 0.0, 0.0),
    "banana": (48.0, 2.0, 0.4),
    "mango": (42.0, 1.5, 0.8),
    "orange": (28.0, -1.0, -0.2),
    "papaya": (50.0, 2.5, 1.0),
    "pineapple": (36.0, 0.5, 0.2),
    "tomato": (40.0, 1.0, 0.5),
    "avocado": (34.0, -0.5, 0.1),
    "durian": (62.0, 3.0, 1.2),
}


def generate_window(rng: np.random.Generator, fruit: str, stage: int) -> tuple[np.ndarray, float]:
    duration_hours = rng.uniform(3.75, 7.5)
    time = np.linspace(0.0, duration_hours, WINDOW_SIZE, dtype=np.float32)
    gas_base, humidity_offset, temperature_offset = FRUIT_PROFILES[fruit]

    if stage == 0:
        risk = rng.uniform(0.05, 0.35)
        gas_slope = rng.uniform(0.0, 4.5)
        humidity_start = rng.uniform(43.0, 61.0) + humidity_offset
        humidity_slope = rng.uniform(-0.25, 0.45)
        temperature_start = rng.uniform(20.0, 26.5) + temperature_offset
        temperature_slope = rng.uniform(-0.15, 0.25)
    elif stage == 1:
        risk = rng.uniform(0.40, 0.68)
        gas_slope = rng.uniform(5.0, 14.0)
        humidity_start = rng.uniform(59.0, 74.0) + humidity_offset
        humidity_slope = rng.uniform(0.3, 1.5)
        temperature_start = rng.uniform(23.5, 29.0) + temperature_offset
        temperature_slope = rng.uniform(0.05, 0.55)
    else:
        risk = rng.uniform(0.72, 0.98)
        gas_slope = rng.uniform(15.0, 36.0)
        humidity_start = rng.uniform(74.0, 91.0) + humidity_offset
        humidity_slope = rng.uniform(0.8, 3.0)
        temperature_start = rng.uniform(27.0, 33.0) + temperature_offset
        temperature_slope = rng.uniform(0.2, 0.9)

    # Sensor-like noise: DHT22 is comparatively stable; MQ-3 is noisier and
    # drifts more. A weak periodic component prevents perfectly straight lines.
    phase = rng.uniform(0.0, np.pi * 2.0)
    temperature = temperature_start + temperature_slope * time + 0.35 * np.sin(time + phase)
    temperature += rng.normal(0.0, 0.22, WINDOW_SIZE)
    humidity = humidity_start + humidity_slope * time + 1.1 * np.sin(time * 0.7 + phase)
    humidity += rng.normal(0.0, 1.25, WINDOW_SIZE)
    gas = gas_base + gas_slope * time + 3.2 * np.sin(time * 1.3 + phase)
    gas += rng.normal(0.0, max(2.0, gas_base * 0.06), WINDOW_SIZE)

    # Rare data-quality faults model a disconnected/stuck channel. The Android
    # pipeline still stores the sample, while real calibration can later add a
    # proper quality flag when firmware exposes one.
    if rng.random() < 0.04:
        channel = int(rng.integers(0, 3))
        values = [temperature, humidity, gas][channel]
        values[:] = values[0] + rng.normal(0.0, 0.04, WINDOW_SIZE)

    window = np.column_stack(
        [
            np.clip(temperature, -10.0, 60.0),
            np.clip(humidity, 0.0, 100.0),
            np.clip(gas, 0.0, 1000.0),
        ]
    ).astype(np.float32)
    return window, float(np.clip(risk, 0.0, 1.0))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=24000)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.samples < 300:
        raise ValueError("Use at least 300 synthetic trajectories for a meaningful experiment.")

    rng = np.random.default_rng(args.seed)
    features = np.zeros((args.samples, WINDOW_SIZE * 3 + len(FRUITS)), dtype=np.float32)
    risks = np.zeros(args.samples, dtype=np.float32)
    classes = np.zeros(args.samples, dtype=np.int64)
    groups = np.arange(args.samples, dtype=np.int64)

    for index in range(args.samples):
        stage = int(rng.choice(3, p=[0.42, 0.33, 0.25]))
        fruit_index = int(rng.integers(0, len(FRUITS)))
        fruit = FRUITS[fruit_index]
        window, risk = generate_window(rng, fruit, stage)
        features[index, : WINDOW_SIZE * 3] = window.reshape(-1)
        features[index, WINDOW_SIZE * 3 + fruit_index] = 1.0
        risks[index] = risk
        classes[index] = stage

    args.output.mkdir(parents=True, exist_ok=True)
    np.save(args.output / "features.npy", features)
    np.save(args.output / "risks.npy", risks)
    np.save(args.output / "classes.npy", classes)
    np.save(args.output / "groups.npy", groups)
    metadata = {
        "dataset_type": "synthetic_sensor_trajectories",
        "warning": "Synthetic only; not field validation and not a replacement for calibrated DHT22/MQ-3 logs.",
        "seed": args.seed,
        "samples": args.samples,
        "window_size": WINDOW_SIZE,
        "sensor_order": ["temperature_c", "humidity_percent", "mq3_level"],
        "fruit_ids": FRUITS,
        "class_names": STAGES,
        "feature_dim": int(features.shape[1]),
        "sampling_interval_hours": [0.25, 0.5],
        "quality_fault_rate": 0.04,
    }
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), **metadata}, indent=2))


if __name__ == "__main__":
    main()
