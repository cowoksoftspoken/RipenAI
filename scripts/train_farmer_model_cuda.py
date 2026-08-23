"""Train the challenging Farmer ML V1 recommender with CUDA PyTorch.

Outputs are risk score, hours-to-action, and safe/attention/urgent
probabilities. The dataset is synthetic and intentionally noisy; its metrics
are useful for regression checks, not field-accuracy claims.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import random
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import torch
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score, mean_absolute_error, recall_score
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = PROJECT / "outputs" / "farmer_synthetic_v1"
DEFAULT_OUTPUT = PROJECT / "outputs" / "farmer_model_v1_cuda"
SEED = 20260823
WINDOW_SIZE = 32
SENSOR_COUNT = 3
FRUIT_COUNT = 9
HORIZON_HOURS = 72.0


class FarmerRiskNet(nn.Module):
    def __init__(self, input_dim: int, fruit_count: int) -> None:
        super().__init__()
        sensor_dim = WINDOW_SIZE * SENSOR_COUNT
        self.sensor_dim = sensor_dim
        self.temporal = nn.Sequential(
            nn.Conv1d(SENSOR_COUNT, 24, kernel_size=5, padding=2),
            nn.BatchNorm1d(24),
            nn.SiLU(),
            nn.Dropout(0.08),
            nn.Conv1d(24, 48, kernel_size=5, padding=2),
            nn.BatchNorm1d(48),
            nn.SiLU(),
            nn.Conv1d(48, 48, kernel_size=3, padding=1),
            nn.SiLU(),
        )
        self.fruit = nn.Sequential(nn.Linear(fruit_count, 16), nn.SiLU())
        self.body = nn.Sequential(
            nn.Linear(48 * 2 + 16, 48),
            nn.LayerNorm(48),
            nn.SiLU(),
            nn.Dropout(0.14),
        )
        self.risk_head = nn.Linear(48, 1)
        self.hours_head = nn.Linear(48, 1)
        self.class_head = nn.Linear(48, 3)

    def forward(self, features: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        sensor = features[:, : self.sensor_dim].reshape(-1, WINDOW_SIZE, SENSOR_COUNT).transpose(1, 2)
        fruit = features[:, self.sensor_dim :]
        encoded = self.temporal(sensor)
        pooled = torch.cat([encoded.mean(dim=2), encoded.amax(dim=2)], dim=1)
        hidden = self.body(torch.cat([pooled, self.fruit(fruit)], dim=1))
        risk = torch.sigmoid(self.risk_head(hidden))
        hours = torch.sigmoid(self.hours_head(hidden))
        logits = self.class_head(hidden)
        return risk, hours, logits


class ExportableFarmerRiskNet(nn.Module):
    def __init__(self, model: FarmerRiskNet) -> None:
        super().__init__()
        self.model = model

    def forward(self, features: torch.Tensor) -> torch.Tensor:
        risk, hours, logits = self.model(features)
        return torch.cat([risk, hours * HORIZON_HOURS, torch.softmax(logits, dim=1)], dim=1)


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def load_data(data_dir: Path, seed: int) -> tuple:
    del seed  # The generator owns the explicit, reproducible group split.
    windows = np.load(data_dir / "sensor_windows.npy").astype(np.float32)
    fruit = np.load(data_dir / "fruit_one_hot.npy").astype(np.float32)
    risks = np.load(data_dir / "risks.npy").astype(np.float32)
    hours = np.load(data_dir / "hours_to_action.npy").astype(np.float32)
    classes = np.load(data_dir / "classes.npy").astype(np.int64)
    split = np.load(data_dir / "split.npy").astype(np.int64)
    scenario_names = np.asarray(json.loads((data_dir / "scenarios.json").read_text(encoding="utf-8")))
    train_mask = split == 0
    mean = windows[train_mask].mean(axis=(0, 1))
    std = windows[train_mask].std(axis=(0, 1))
    std[std < 1e-5] = 1.0
    normalized_windows = (windows - mean.reshape(1, 1, -1)) / std.reshape(1, 1, -1)
    features = np.concatenate([normalized_windows.reshape(len(windows), -1), fruit], axis=1).astype(np.float32)
    return features, risks, hours / HORIZON_HOURS, classes, split, scenario_names, mean, std


def make_loader(features: np.ndarray, risks: np.ndarray, hours: np.ndarray, classes: np.ndarray, batch_size: int, shuffle: bool) -> DataLoader:
    dataset = TensorDataset(
        torch.from_numpy(features),
        torch.from_numpy(risks).unsqueeze(1),
        torch.from_numpy(hours).unsqueeze(1),
        torch.from_numpy(classes),
    )
    return DataLoader(dataset, batch_size=batch_size, shuffle=shuffle, num_workers=0, pin_memory=True)


def run_epoch(model: FarmerRiskNet, loader: DataLoader, optimizer: torch.optim.Optimizer | None, scaler: torch.amp.GradScaler, class_weights: torch.Tensor) -> float:
    training = optimizer is not None
    model.train(training)
    total_loss = 0.0
    total = 0
    for features, risks, hours, classes in loader:
        features = features.cuda(non_blocking=True)
        risks = risks.cuda(non_blocking=True)
        hours = hours.cuda(non_blocking=True)
        classes = classes.cuda(non_blocking=True)
        if training:
            optimizer.zero_grad(set_to_none=True)
        with torch.amp.autocast("cuda", dtype=torch.float16):
            predicted_risk, predicted_hours, logits = model(features)
            loss = (
                0.75 * nn.functional.smooth_l1_loss(predicted_risk, risks)
                + 0.65 * nn.functional.smooth_l1_loss(predicted_hours, hours)
                + 0.45 * nn.functional.cross_entropy(logits, classes, weight=class_weights, label_smoothing=0.035)
            )
        if training:
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            nn.utils.clip_grad_norm_(model.parameters(), max_norm=2.0)
            scaler.step(optimizer)
            scaler.update()
        total_loss += float(loss.detach().item()) * features.size(0)
        total += features.size(0)
    return total_loss / max(total, 1)


@torch.no_grad()
def predict(model: FarmerRiskNet, loader: DataLoader) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    model.eval()
    risks: list[np.ndarray] = []
    hours: list[np.ndarray] = []
    probabilities: list[np.ndarray] = []
    labels: list[np.ndarray] = []
    for features, _target_risk, _target_hours, target_class in loader:
        predicted_risk, predicted_hours, logits = model(features.cuda(non_blocking=True))
        risks.append(predicted_risk.squeeze(1).float().cpu().numpy())
        hours.append((predicted_hours.squeeze(1) * HORIZON_HOURS).float().cpu().numpy())
        probabilities.append(torch.softmax(logits, dim=1).float().cpu().numpy())
        labels.append(target_class.numpy())
    return np.concatenate(risks), np.concatenate(hours), np.concatenate(probabilities), np.concatenate(labels)


def evaluate(model: FarmerRiskNet, loader: DataLoader, target_risks: np.ndarray, target_hours: np.ndarray, target_classes: np.ndarray) -> dict:
    predicted_risk, predicted_hours, probabilities, labels = predict(model, loader)
    predicted_classes = probabilities.argmax(axis=1)
    matrix = confusion_matrix(labels, predicted_classes, labels=[0, 1, 2]).astype(int).tolist()
    return {
        "risk_mae": float(mean_absolute_error(target_risks, predicted_risk)),
        "hours_to_action_mae": float(mean_absolute_error(target_hours, predicted_hours)),
        "class_accuracy": float(accuracy_score(labels, predicted_classes)),
        "class_macro_f1": float(f1_score(labels, predicted_classes, average="macro")),
        "urgent_recall": float(recall_score(labels, predicted_classes, labels=[0, 1, 2], average=None, zero_division=0)[2]),
        "confusion_matrix": matrix,
        "support": int(len(labels)),
    }


def evaluate_scenarios(model: FarmerRiskNet, features: np.ndarray, risks: np.ndarray, hours: np.ndarray, classes: np.ndarray, scenarios: np.ndarray, scenario_names: list[str], batch_size: int) -> dict:
    results = {}
    for scenario in sorted(set(scenario_names)):
        mask = scenarios == scenario
        if int(mask.sum()) < 8:
            continue
        loader = make_loader(features[mask], risks[mask], hours[mask] / HORIZON_HOURS, classes[mask], batch_size, False)
        result = evaluate(model, loader, risks[mask], hours[mask], classes[mask])
        results[scenario] = result
    return results


def plot_report(history: list[dict], holdout: dict, ood: dict, output_dir: Path) -> None:
    figure, axes = plt.subplots(2, 2, figsize=(12, 8))
    epochs = [item["epoch"] for item in history]
    axes[0, 0].plot(epochs, [item["train_loss"] for item in history], label="train loss")
    axes[0, 0].plot(epochs, [item["val_risk_mae"] for item in history], label="val risk MAE")
    axes[0, 0].plot(epochs, [item["val_hours_mae"] / HORIZON_HOURS for item in history], label="val hours MAE / 72")
    axes[0, 0].set_title("Farmer ML V1 training on difficult synthetic data")
    axes[0, 0].set_xlabel("Epoch")
    axes[0, 0].legend()
    for title, report, axis in (("Standard holdout", holdout, axes[0, 1]), ("OOD holdout", ood, axes[1, 0])):
        matrix = np.asarray(report["confusion_matrix"], dtype=np.float32)
        axis.imshow(matrix, cmap="Blues", vmin=0)
        axis.set_title(title)
        axis.set_xlabel("Predicted")
        axis.set_ylabel("Actual")
        for row in range(3):
            for column in range(3):
                axis.text(column, row, int(matrix[row, column]), ha="center", va="center")
    names = list(ood.get("per_scenario", {}).keys())
    values = [ood["per_scenario"][name]["hours_to_action_mae"] for name in names]
    axes[1, 1].bar(names, values, color="#0F9D58")
    axes[1, 1].set_title("OOD hours-to-action MAE")
    axes[1, 1].tick_params(axis="x", rotation=35)
    axes[1, 1].set_ylabel("Hours")
    figure.tight_layout()
    figure.savefig(output_dir / "farmer_model_report.png", dpi=160, facecolor="white")
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--epochs", type=int, default=int(os.environ.get("RIPEN_FARMER_V1_EPOCHS", "45")))
    parser.add_argument("--batch-size", type=int, default=int(os.environ.get("RIPEN_FARMER_V1_BATCH_SIZE", "256")))
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; refusing to train Farmer ML V1 on CPU.")
    seed_everything(args.seed)
    torch.set_float32_matmul_precision("high")
    args.output.mkdir(parents=True, exist_ok=True)
    features, risks, hours, classes, split, scenarios, mean, std = load_data(args.data, args.seed)
    train_mask, val_mask, holdout_mask, ood_mask = split == 0, split == 1, split == 2, split == 3
    train_loader = make_loader(features[train_mask], risks[train_mask], hours[train_mask], classes[train_mask], args.batch_size, True)
    val_loader = make_loader(features[val_mask], risks[val_mask], hours[val_mask], classes[val_mask], args.batch_size, False)
    holdout_loader = make_loader(features[holdout_mask], risks[holdout_mask], hours[holdout_mask], classes[holdout_mask], args.batch_size, False)
    ood_loader = make_loader(features[ood_mask], risks[ood_mask], hours[ood_mask], classes[ood_mask], args.batch_size, False)
    model = FarmerRiskNet(features.shape[1], FRUIT_COUNT).cuda()
    train_class_counts = np.bincount(classes[train_mask], minlength=3).astype(np.float32)
    class_weights = torch.sqrt(torch.tensor(train_class_counts.sum() / (3.0 * train_class_counts), dtype=torch.float32, device="cuda"))
    class_weights = class_weights / class_weights.mean()
    optimizer = torch.optim.AdamW(model.parameters(), lr=1.6e-3, weight_decay=3e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(args.epochs, 1), eta_min=1e-5)
    scaler = torch.amp.GradScaler("cuda")
    best_state: dict[str, torch.Tensor] | None = None
    best_val = float("inf")
    history: list[dict] = []
    print(f"CUDA device: {torch.cuda.get_device_name(0)}")
    print(f"Farmer ML V1 data: train={train_mask.sum()} val={val_mask.sum()} holdout={holdout_mask.sum()} ood={ood_mask.sum()} features={features.shape[1]}", flush=True)
    for epoch in range(1, args.epochs + 1):
        train_loss = run_epoch(model, train_loader, optimizer, scaler, class_weights)
        val_report = evaluate(model, val_loader, risks[val_mask], hours[val_mask] * HORIZON_HOURS, classes[val_mask])
        scheduler.step()
        record = {
            "epoch": epoch,
            "train_loss": train_loss,
            "val_risk_mae": val_report["risk_mae"],
            "val_hours_mae": val_report["hours_to_action_mae"],
            "val_accuracy": val_report["class_accuracy"],
        }
        history.append(record)
        print(json.dumps(record), flush=True)
        selection_score = val_report["risk_mae"] + val_report["hours_to_action_mae"] / HORIZON_HOURS + 0.25 * (1.0 - val_report["class_macro_f1"])
        if selection_score < best_val:
            best_val = selection_score
            best_state = copy.deepcopy(model.state_dict())
    if best_state is None:
        raise RuntimeError("No Farmer ML V1 checkpoint was produced")
    model.load_state_dict(best_state)
    holdout = evaluate(model, holdout_loader, risks[holdout_mask], hours[holdout_mask] * HORIZON_HOURS, classes[holdout_mask])
    ood = evaluate(model, ood_loader, risks[ood_mask], hours[ood_mask] * HORIZON_HOURS, classes[ood_mask])
    ood["per_scenario"] = evaluate_scenarios(model, features[ood_mask], risks[ood_mask], hours[ood_mask] * HORIZON_HOURS, classes[ood_mask], scenarios[ood_mask], sorted(set(scenarios[ood_mask])), args.batch_size)
    torch.save({"state_dict": model.state_dict(), "history": history, "holdout": holdout, "ood": ood}, args.output / "farmer_risk_cuda.pt")
    exported = ExportableFarmerRiskNet(model).eval().cuda()
    onnx_path = args.output / "farmer_risk_cuda.onnx"
    dummy = torch.zeros(1, features.shape[1], device="cuda")
    torch.onnx.export(exported, dummy, onnx_path, input_names=["features"], output_names=["risk_hours_probabilities"], opset_version=17, dynamo=False)
    metadata = json.loads((args.data / "metadata.json").read_text(encoding="utf-8"))
    # Training normalizes only the three sensor channels and leaves the fruit
    # one-hot vector unchanged. Persist the expanded 105-element contract so
    # Android can reproduce the exact preprocessing without guessing.
    feature_mean = np.concatenate([np.tile(mean, WINDOW_SIZE), np.zeros(FRUIT_COUNT, dtype=np.float32)])
    feature_std = np.concatenate([np.tile(std, WINDOW_SIZE), np.ones(FRUIT_COUNT, dtype=np.float32)])
    metadata.update({
        "model_type": "temporal_conv_multitask",
        "model_version": "farmer-synthetic-v1",
        "feature_mean": feature_mean.tolist(),
        "feature_std": feature_std.tolist(),
        "output_order": ["risk_score", "hours_to_action", "safe_probability", "attention_probability", "urgent_probability"],
        "class_weights": class_weights.detach().cpu().tolist(),
        "training_warning": "Trained only on difficult synthetic DHT22/MQ-3 proxy trajectories. Do not claim field accuracy before real calibration logs and manual labels.",
        "validation": evaluate(model, val_loader, risks[val_mask], hours[val_mask] * HORIZON_HOURS, classes[val_mask]),
        "holdout": holdout,
        "ood": ood,
    })
    (args.output / "farmer_model_config.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (args.output / "farmer_model_history.json").write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")
    plot_report(history, holdout, ood, args.output)
    print(json.dumps({"holdout": holdout, "ood": ood, "onnx": str(onnx_path)}, indent=2))


if __name__ == "__main__":
    main()
