"""Generate difficult, physics-inspired synthetic sensor trajectories for farmer V2.

This is a bootstrap dataset, not field truth. The generator intentionally models
overlapping conditions, confounders, sensor drift, missing/outlier samples, and
noisy manual labels so a high score cannot be obtained by memorising an easy
stage-to-range mapping.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = PROJECT / "outputs" / "farmer_synthetic_v2"
SEED = 20260823
WINDOW_SIZE = 32
TOTAL_STEPS = 96
DT_HOURS = 0.25
FRUITS = ["apple", "banana", "mango", "orange", "papaya", "pineapple", "tomato", "avocado", "durian"]
TRAIN_SCENARIOS = ["normal", "warm", "humid", "dry", "ventilation", "sensor_drift", "door_opening", "gas_burst"]
OOD_SCENARIOS = ["mixed_stress"]
ALL_SCENARIOS = TRAIN_SCENARIOS + OOD_SCENARIOS


@dataclass(frozen=True)
class FruitProfile:
    gas_baseline: float
    gas_gain: float
    ripening_rate: float
    humidity_sensitivity: float
    temp_preference: float


FRUIT_PROFILES = {
    "apple": FruitProfile(125.0, 300.0, 0.0100, 0.90, 20.0),
    "banana": FruitProfile(150.0, 430.0, 0.0140, 1.10, 23.0),
    "mango": FruitProfile(135.0, 390.0, 0.0120, 1.05, 24.0),
    "orange": FruitProfile(110.0, 230.0, 0.0075, 0.75, 19.0),
    "papaya": FruitProfile(165.0, 480.0, 0.0150, 1.20, 25.0),
    "pineapple": FruitProfile(130.0, 270.0, 0.0080, 0.80, 22.0),
    "tomato": FruitProfile(145.0, 340.0, 0.0110, 1.00, 21.0),
    "avocado": FruitProfile(120.0, 310.0, 0.0085, 0.85, 20.0),
    "durian": FruitProfile(205.0, 570.0, 0.0130, 1.35, 26.0),
}


def _scenario_settings(scenario: str) -> tuple[float, float, float, float]:
    """Return temperature shift, humidity shift, gas multiplier, disturbance."""
    return {
        "normal": (0.0, 0.0, 1.00, 0.10),
        "warm": (4.5, 2.0, 1.02, 0.15),
        "humid": (1.0, 14.0, 1.00, 0.12),
        "dry": (1.5, -15.0, 0.98, 0.12),
        "ventilation": (-1.5, -8.0, 0.72, 0.22),
        "sensor_drift": (0.5, 4.0, 1.00, 0.30),
        "door_opening": (0.0, 1.0, 0.95, 0.75),
        "gas_burst": (0.5, 4.0, 1.00, 0.40),
        "mixed_stress": (5.5, 16.0, 0.86, 0.85),
    }[scenario]


def simulate_trajectory(
    rng: np.random.Generator,
    fruit: str,
    scenario: str,
    total_steps: int = TOTAL_STEPS,
) -> dict[str, np.ndarray | float | str]:
    """Simulate latent fruit/storage dynamics and imperfect sensor observations."""
    profile = FRUIT_PROFILES[fruit]
    temp_shift, humidity_shift, gas_multiplier, disturbance = _scenario_settings(scenario)
    phase = rng.uniform(0.30, 0.82)
    temperature_base = rng.uniform(20.0, 31.0) + temp_shift
    humidity_base = rng.uniform(46.0, 79.0) + humidity_shift
    rate_multiplier = rng.lognormal(mean=0.0, sigma=0.22)
    gas_offset = rng.normal(0.0, 30.0)
    gas_gain_multiplier = rng.uniform(0.82, 1.20)
    temp_bias = rng.normal(0.0, 0.55)
    humidity_bias = rng.normal(0.0, 2.4)
    gas_drift = rng.normal(0.0, 0.18) if scenario in {"sensor_drift", "mixed_stress"} else rng.normal(0.0, 0.04)
    burst_start = int(rng.integers(28, 78))
    burst_length = int(rng.integers(2, 9))
    door_start = int(rng.integers(24, 78))
    door_length = int(rng.integers(2, 7))

    latent_quality = np.zeros(total_steps, dtype=np.float32)
    latent_mold = np.zeros(total_steps, dtype=np.float32)
    true_temperature = np.zeros(total_steps, dtype=np.float32)
    true_humidity = np.zeros(total_steps, dtype=np.float32)
    gas_signal = np.zeros(total_steps, dtype=np.float32)
    quality = phase
    mold = 0.0
    for index in range(total_steps):
        time_hours = index * DT_HOURS
        circadian = 0.8 * np.sin(time_hours / 3.8 + rng.uniform(-0.2, 0.2))
        door_effect = 0.0
        if scenario in {"door_opening", "mixed_stress"} and door_start <= index < door_start + door_length:
            door_effect = -rng.uniform(3.0, 7.0)
        temperature = temperature_base + circadian + door_effect + rng.normal(0.0, 0.45)
        humidity = humidity_base - door_effect * 0.55 + 1.5 * np.sin(time_hours / 4.5) + rng.normal(0.0, 2.2)
        humidity = float(np.clip(humidity, 24.0, 96.0))
        temp_factor = float(np.clip(np.exp(0.055 * (temperature - profile.temp_preference)), 0.65, 1.75))
        humidity_stress = max(0.0, (humidity - 75.0) / 25.0)
        rate = profile.ripening_rate * rate_multiplier * temp_factor * (1.0 + 0.22 * humidity_stress)
        quality = float(np.clip(quality + rate * DT_HOURS + rng.normal(0.0, 0.0018), 0.0, 1.15))
        if humidity > 78.0:
            mold += (humidity - 78.0) / 22.0 * profile.humidity_sensitivity * 0.007 * DT_HOURS
        else:
            mold = max(0.0, mold - 0.001 * DT_HOURS)
        mold = float(np.clip(mold, 0.0, 1.0))

        maturity_gas = profile.gas_gain * gas_gain_multiplier * np.power(np.clip(quality, 0.0, 1.2), 1.35)
        humidity_cross_sensitivity = max(0.0, humidity - 55.0) * rng.uniform(0.65, 1.35)
        burst = 0.0
        if scenario in {"gas_burst", "mixed_stress"} and burst_start <= index < burst_start + burst_length:
            burst = rng.uniform(100.0, 280.0) * (1.0 if scenario == "gas_burst" else 0.75)
        ventilation_loss = 0.0
        if scenario in {"ventilation", "mixed_stress"}:
            ventilation_loss = rng.uniform(30.0, 90.0) * (index / max(total_steps - 1, 1))
        gas_signal[index] = profile.gas_baseline + maturity_gas * gas_multiplier + humidity_cross_sensitivity + burst - ventilation_loss
        true_temperature[index] = temperature
        true_humidity[index] = humidity
        latent_quality[index] = quality
        latent_mold[index] = mold

    # The MQ-3 is a broad alcohol/VOC proxy, not an ethylene sensor. Its
    # reading therefore includes cross-sensitivity, gain error, drift, noise,
    # warm-up distortion, and occasional independent gas bursts.
    time_fraction = np.linspace(0.0, 1.0, total_steps, dtype=np.float32)
    warmup = np.where(np.arange(total_steps) < 8, rng.uniform(-70.0, 35.0) * (1.0 - np.arange(total_steps) / 8.0), 0.0)
    gas_observed = gas_signal * (1.0 + gas_drift * time_fraction) + gas_offset + warmup
    gas_observed += rng.normal(0.0, 15.0 + 0.055 * np.maximum(gas_signal, 0.0), total_steps)
    temp_observed = true_temperature + temp_bias + rng.normal(0.0, 0.28, total_steps)
    humidity_observed = true_humidity + humidity_bias + rng.normal(0.0, 1.55, total_steps)

    # Faults make the task closer to actual low-cost hardware and are not
    # surfaced as a perfect quality flag to the model.
    if rng.random() < 0.32:
        channel = int(rng.integers(0, 3))
        start = int(rng.integers(8, total_steps - 8))
        length = int(rng.integers(3, 15))
        values = [temp_observed, humidity_observed, gas_observed][channel]
        values[start : start + length] = values[start] + rng.normal(0.0, 0.08, min(length, total_steps - start))
    for values, noise in ((temp_observed, 1.8), (humidity_observed, 7.0), (gas_observed, 95.0)):
        outlier_mask = rng.random(total_steps) < 0.025
        values[outlier_mask] += rng.normal(0.0, noise, int(outlier_mask.sum()))
    if scenario in {"door_opening", "mixed_stress"}:
        temp_observed += disturbance * np.sin(np.arange(total_steps) * 0.9) * rng.uniform(1.0, 3.0)
    temp_observed = np.clip(temp_observed, -10.0, 60.0).astype(np.float32)
    humidity_observed = np.clip(humidity_observed, 0.0, 100.0).astype(np.float32)
    gas_observed = np.clip(gas_observed, 0.0, 1000.0).astype(np.float32)

    latent_risk = np.maximum(
        np.clip((latent_quality - 0.76) / 0.24, 0.0, 1.0),
        np.clip(latent_mold / 0.52, 0.0, 1.0),
    )
    end_index = int(rng.integers(WINDOW_SIZE, total_steps - 8))
    window_start = end_index - WINDOW_SIZE
    future = np.flatnonzero(latent_risk[end_index:] >= 0.72)
    if len(future) == 0:
        hours_to_action = 72.0
    else:
        hours_to_action = float(future[0] * DT_HOURS)
    horizon_signal = float(np.exp(-hours_to_action / 20.0))
    latent_target = float(np.clip(0.58 * latent_risk[end_index - 1] + 0.42 * horizon_signal, 0.0, 1.0))
    observed_label = float(np.clip(latent_target + rng.normal(0.0, 0.065), 0.0, 1.0))
    class_id = int(np.digitize(observed_label, [0.40, 0.70]))
    if rng.random() < 0.08:
        class_id = int(np.clip(class_id + rng.choice([-1, 1]), 0, 2))

    sensor_window = np.column_stack(
        [temp_observed[window_start:end_index], humidity_observed[window_start:end_index], gas_observed[window_start:end_index]]
    ).astype(np.float32)
    return {
        "sensor_window": sensor_window,
        "fruit": fruit,
        "scenario": scenario,
        "risk": observed_label,
        "hours_to_action": hours_to_action,
        "class_id": class_id,
        "latent_risk": float(latent_target),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=12000)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.samples < 900:
        raise ValueError("Use at least 900 trajectories for the V2 split and OOD evaluation.")

    rng = np.random.default_rng(args.seed)
    train_count = int(args.samples * 0.67)
    val_count = int(args.samples * 0.13)
    holdout_count = int(args.samples * 0.10)
    ood_count = args.samples - train_count - val_count - holdout_count
    rows: list[dict] = []
    split_ids: list[int] = []
    counts = [(train_count, 0, TRAIN_SCENARIOS), (val_count, 1, TRAIN_SCENARIOS), (holdout_count, 2, TRAIN_SCENARIOS), (ood_count, 3, OOD_SCENARIOS)]
    for count, split_id, scenarios in counts:
        for _ in range(count):
            fruit = FRUITS[int(rng.integers(0, len(FRUITS)))]
            scenario = scenarios[int(rng.integers(0, len(scenarios)))]
            rows.append(simulate_trajectory(rng, fruit, scenario))
            split_ids.append(split_id)

    sensor_windows = np.stack([row["sensor_window"] for row in rows]).astype(np.float32)
    fruit_one_hot = np.zeros((len(rows), len(FRUITS)), dtype=np.float32)
    fruit_index = np.asarray([FRUITS.index(str(row["fruit"])) for row in rows], dtype=np.int64)
    fruit_one_hot[np.arange(len(rows)), fruit_index] = 1.0
    args.output.mkdir(parents=True, exist_ok=True)
    np.save(args.output / "sensor_windows.npy", sensor_windows)
    np.save(args.output / "fruit_one_hot.npy", fruit_one_hot)
    np.save(args.output / "risks.npy", np.asarray([row["risk"] for row in rows], dtype=np.float32))
    np.save(args.output / "hours_to_action.npy", np.asarray([row["hours_to_action"] for row in rows], dtype=np.float32))
    np.save(args.output / "classes.npy", np.asarray([row["class_id"] for row in rows], dtype=np.int64))
    np.save(args.output / "latent_risks.npy", np.asarray([row["latent_risk"] for row in rows], dtype=np.float32))
    np.save(args.output / "split.npy", np.asarray(split_ids, dtype=np.int64))
    (args.output / "scenarios.json").write_text(json.dumps([row["scenario"] for row in rows]) + "\n", encoding="utf-8")
    metadata = {
        "dataset_type": "synthetic_sensor_trajectories_v2",
        "warning": "Synthetic only; no field accuracy claim. Targets include proxy/manual-label noise and require real DHT22/MQ-3 validation.",
        "seed": args.seed,
        "samples": len(rows),
        "window_size": WINDOW_SIZE,
        "total_simulated_steps": TOTAL_STEPS,
        "sampling_interval_minutes": 15,
        "sensor_order": ["temperature_c", "humidity_percent", "mq3_proxy_adc"],
        "fruit_ids": FRUITS,
        "class_names": ["safe", "attention", "urgent"],
        "scenario_names": ALL_SCENARIOS,
        "train_scenarios": TRAIN_SCENARIOS,
        "ood_scenarios": OOD_SCENARIOS,
        "quality_fault_rate": 0.32,
        "outlier_rate_per_channel": 0.025,
        "label_noise_std": 0.065,
        "feature_dim": WINDOW_SIZE * 3 + len(FRUITS),
        "split_counts": {"train": train_count, "validation": val_count, "holdout": holdout_count, "ood": ood_count},
    }
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), **metadata}, indent=2))


if __name__ == "__main__":
    main()
