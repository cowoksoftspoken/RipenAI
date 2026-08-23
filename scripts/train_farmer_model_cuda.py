"""Train the experimental farmer risk model on synthetic DHT22 + MQ-3 data.

The model is intentionally small enough for on-device inference. It predicts a
continuous risk score plus safe/attention/urgent probabilities. It is an
assistive model only: Android keeps the transparent rule-based score as the
primary fallback until real, calibrated sensor logs are available.
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
from sklearn.model_selection import GroupShuffleSplit
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = PROJECT / "outputs" / "farmer_synthetic"
DEFAULT_OUTPUT = PROJECT / "outputs" / "farmer_model_cuda"
SEED = 20260823


class FarmerRiskNet(nn.Module):
    def __init__(self, input_dim: int) -> None:
        super().__init__()
        self.body = nn.Sequential(
            nn.Linear(input_dim, 64),
            nn.LayerNorm(64),
            nn.ReLU(),
            nn.Dropout(0.10),
            nn.Linear(64, 32),
            nn.ReLU(),
        )
        self.risk_head = nn.Linear(32, 1)
        self.class_head = nn.Linear(32, 3)

    def forward(self, features: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        hidden = self.body(features)
        return torch.sigmoid(self.risk_head(hidden)), self.class_head(hidden)


class ExportableFarmerRiskNet(nn.Module):
    def __init__(self, model: FarmerRiskNet) -> None:
        super().__init__()
        self.model = model

    def forward(self, features: torch.Tensor) -> torch.Tensor:
        risk, logits = self.model(features)
        return torch.cat([risk, torch.softmax(logits, dim=1)], dim=1)


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def load_data(data_dir: Path, seed: int) -> tuple[np.ndarray, ...]:
    features = np.load(data_dir / "features.npy").astype(np.float32)
    risks = np.load(data_dir / "risks.npy").astype(np.float32)
    classes = np.load(data_dir / "classes.npy").astype(np.int64)
    groups = np.load(data_dir / "groups.npy").astype(np.int64)
    first, holdout = next(GroupShuffleSplit(n_splits=1, test_size=0.20, random_state=seed).split(features, classes, groups))
    train, val = next(GroupShuffleSplit(n_splits=1, test_size=0.125, random_state=seed + 1).split(features[first], classes[first], groups[first]))
    train_idx = first[train]
    val_idx = first[val]
    mean = features[train_idx].mean(axis=0)
    std = features[train_idx].std(axis=0)
    std[std < 1e-6] = 1.0
    normalized = (features - mean) / std
    return (
        normalized[train_idx], risks[train_idx], classes[train_idx],
        normalized[val_idx], risks[val_idx], classes[val_idx],
        normalized[holdout], risks[holdout], classes[holdout], mean, std,
    )


def make_loader(features: np.ndarray, risks: np.ndarray, classes: np.ndarray, batch_size: int, shuffle: bool) -> DataLoader:
    dataset = TensorDataset(
        torch.from_numpy(features),
        torch.from_numpy(risks).unsqueeze(1),
        torch.from_numpy(classes),
    )
    return DataLoader(dataset, batch_size=batch_size, shuffle=shuffle, num_workers=0, pin_memory=True)


def run_epoch(model: FarmerRiskNet, loader: DataLoader, optimizer: torch.optim.Optimizer | None, scaler: torch.amp.GradScaler) -> float:
    training = optimizer is not None
    model.train(training)
    total_loss = 0.0
    total = 0
    for features, risks, classes in loader:
        features = features.cuda(non_blocking=True)
        risks = risks.cuda(non_blocking=True)
        classes = classes.cuda(non_blocking=True)
        if training:
            optimizer.zero_grad(set_to_none=True)
        with torch.amp.autocast("cuda", dtype=torch.float16):
            predicted_risk, logits = model(features)
            loss = nn.functional.mse_loss(predicted_risk, risks) + 0.50 * nn.functional.cross_entropy(logits, classes)
        if training:
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
        total_loss += float(loss.detach().item()) * features.size(0)
        total += features.size(0)
    return total_loss / max(total, 1)


@torch.no_grad()
def predict(model: FarmerRiskNet, loader: DataLoader) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    model.eval()
    risks: list[np.ndarray] = []
    probabilities: list[np.ndarray] = []
    labels: list[np.ndarray] = []
    for features, _target_risk, target_class in loader:
        predicted_risk, logits = model(features.cuda(non_blocking=True))
        risks.append(predicted_risk.squeeze(1).float().cpu().numpy())
        probabilities.append(torch.softmax(logits, dim=1).float().cpu().numpy())
        labels.append(target_class.numpy())
    return np.concatenate(risks), np.concatenate(probabilities), np.concatenate(labels)


def metrics(model: FarmerRiskNet, loader: DataLoader, target_risks: np.ndarray) -> dict:
    predicted_risk, probabilities, labels = predict(model, loader)
    predicted_class = probabilities.argmax(axis=1)
    matrix = confusion_matrix(labels, predicted_class, labels=[0, 1, 2]).tolist()
    return {
        "risk_mae": float(mean_absolute_error(target_risks, predicted_risk)),
        "class_accuracy": float(accuracy_score(labels, predicted_class)),
        "class_macro_f1": float(f1_score(labels, predicted_class, average="macro")),
        "urgent_recall": float(recall_score(labels, predicted_class, labels=[0, 1, 2], average=None, zero_division=0)[2]),
        "confusion_matrix": matrix,
        "support": int(len(labels)),
    }


def plot_report(history: list[dict], holdout: dict, output_dir: Path) -> None:
    figure, axes = plt.subplots(1, 2, figsize=(12, 4.5))
    axes[0].plot([item["epoch"] for item in history], [item["train_loss"] for item in history], label="train")
    axes[0].plot([item["epoch"] for item in history], [item["val_risk_mae"] for item in history], label="val risk MAE")
    axes[0].set_title("Synthetic farmer model training")
    axes[0].set_xlabel("Epoch")
    axes[0].legend()
    matrix = np.asarray(holdout["confusion_matrix"], dtype=np.float32)
    axes[1].imshow(matrix, cmap="Blues", vmin=0)
    axes[1].set_title("Holdout confusion matrix")
    axes[1].set_xlabel("Predicted")
    axes[1].set_ylabel("Actual")
    for row in range(3):
        for column in range(3):
            axes[1].text(column, row, int(matrix[row, column]), ha="center", va="center")
    figure.tight_layout()
    figure.savefig(output_dir / "farmer_model_report.png", dpi=160, facecolor="white")
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--epochs", type=int, default=int(os.environ.get("RIPEN_FARMER_EPOCHS", "35")))
    parser.add_argument("--batch-size", type=int, default=int(os.environ.get("RIPEN_FARMER_BATCH_SIZE", "512")))
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; refusing to train the farmer candidate on CPU.")
    seed_everything(args.seed)
    torch.set_float32_matmul_precision("high")
    args.output.mkdir(parents=True, exist_ok=True)
    data = load_data(args.data, args.seed)
    train_x, train_risk, train_class, val_x, val_risk, val_class, test_x, test_risk, test_class, mean, std = data
    train_loader = make_loader(train_x, train_risk, train_class, args.batch_size, True)
    val_loader = make_loader(val_x, val_risk, val_class, args.batch_size, False)
    test_loader = make_loader(test_x, test_risk, test_class, args.batch_size, False)
    model = FarmerRiskNet(train_x.shape[1]).cuda()
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(args.epochs, 1))
    scaler = torch.amp.GradScaler("cuda")
    best_state: dict[str, torch.Tensor] | None = None
    best_val = float("inf")
    history: list[dict] = []
    print(f"CUDA device: {torch.cuda.get_device_name(0)}")
    print(f"Synthetic data: train={len(train_x)} val={len(val_x)} test={len(test_x)} features={train_x.shape[1]}", flush=True)
    for epoch in range(1, args.epochs + 1):
        train_loss = run_epoch(model, train_loader, optimizer, scaler)
        val_metrics = metrics(model, val_loader, val_risk)
        scheduler.step()
        record = {"epoch": epoch, "train_loss": train_loss, "val_risk_mae": val_metrics["risk_mae"], "val_accuracy": val_metrics["class_accuracy"]}
        history.append(record)
        print(json.dumps(record), flush=True)
        if val_metrics["risk_mae"] < best_val:
            best_val = val_metrics["risk_mae"]
            best_state = copy.deepcopy(model.state_dict())
    if best_state is None:
        raise RuntimeError("No farmer model checkpoint was produced")
    model.load_state_dict(best_state)
    holdout = metrics(model, test_loader, test_risk)
    validation = metrics(model, val_loader, val_risk)
    torch.save({"state_dict": model.state_dict(), "history": history, "holdout": holdout}, args.output / "farmer_risk_cuda.pt")
    exported = ExportableFarmerRiskNet(model).eval().cuda()
    onnx_path = args.output / "farmer_risk_cuda.onnx"
    dummy = torch.zeros(1, train_x.shape[1], device="cuda")
    torch.onnx.export(exported, dummy, onnx_path, input_names=["features"], output_names=["risk_and_probabilities"], opset_version=17, dynamo=False)
    metadata = json.loads((args.data / "metadata.json").read_text(encoding="utf-8"))
    metadata.update({
        "model_type": "small_mlp_multitask",
        "model_version": "farmer-synthetic-v1-experimental",
        "feature_mean": mean.tolist(),
        "feature_std": std.tolist(),
        "output_order": ["risk_score", "safe_probability", "attention_probability", "urgent_probability"],
        "training_warning": "Trained only on synthetic DHT22/MQ-3 trajectories. Do not claim field accuracy before real calibration logs.",
        "validation": validation,
        "holdout": holdout,
    })
    (args.output / "farmer_model_config.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (args.output / "farmer_model_history.json").write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")
    plot_report(history, holdout, args.output)
    print(json.dumps({"holdout": holdout, "validation": validation, "onnx": str(onnx_path)}, indent=2))


if __name__ == "__main__":
    main()
