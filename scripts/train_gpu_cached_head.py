"""Cache MobileNetV2 features on CUDA, train the classifier head on CUDA.

This avoids recomputing a frozen backbone for every head epoch. The final
Keras model still contains the original MobileNetV2 graph and is exportable to
the existing TFLite Android contract.
"""

from __future__ import annotations

import os

os.environ.setdefault("KERAS_BACKEND", "torch")

import json
import random
from pathlib import Path

import keras
import numpy as np
import torch
from PIL import Image, ImageEnhance, ImageOps
from torch import nn
from torch.utils.data import DataLoader, Dataset, TensorDataset


PROJECT = Path(__file__).resolve().parents[1]
PROCESSED = PROJECT / "data" / "processed"
OUTPUTS = PROJECT / "outputs"
SIZE = 224
SEED = 42
BATCH_SIZE = int(os.environ.get("RIPEN_GPU_CACHE_BATCH_SIZE", "32"))
HEAD_EPOCHS = int(os.environ.get("RIPEN_GPU_CACHED_HEAD_EPOCHS", "10"))
WORKERS = int(os.environ.get("RIPEN_GPU_WORKERS", "2"))


class ImageDataset(Dataset):
    def __init__(self, root: Path, names: list[str], training: bool) -> None:
        self.training = training
        index = {name: value for value, name in enumerate(names)}
        self.items = [(path, index[path.parent.name]) for path in sorted(root.glob("*/*")) if path.is_file()]

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, item: int) -> tuple[torch.Tensor, int]:
        path, label = self.items[item]
        with Image.open(path) as source:
            image = ImageOps.fit(source.convert("RGB"), (SIZE, SIZE), method=Image.Resampling.BILINEAR)
        if self.training:
            if random.random() < 0.5:
                image = ImageOps.mirror(image)
            if random.random() < 0.30:
                image = ImageEnhance.Brightness(image).enhance(random.uniform(0.88, 1.12))
            if random.random() < 0.30:
                image = ImageEnhance.Contrast(image).enhance(random.uniform(0.88, 1.12))
        return torch.from_numpy(np.asarray(image, dtype=np.float32)), label


def names() -> list[str]:
    return sorted(path.name for path in (PROCESSED / "train").iterdir() if path.is_dir())


def build_feature_model(number_of_classes: int):
    with keras.device("cuda"):
        inputs = keras.Input(shape=(SIZE, SIZE, 3), name="image")
        x = keras.applications.mobilenet_v2.preprocess_input(inputs)
        base = keras.applications.MobileNetV2(weights="imagenet", include_top=False, input_shape=(SIZE, SIZE, 3))
        base.trainable = False
        pooled = keras.layers.GlobalAveragePooling2D(name="global_average_pooling2d")(base(x, training=False))
        first = keras.layers.Dropout(0.30, name="dropout")(pooled)
        first = keras.layers.Dense(128, activation="relu", name="dense")(first)
        second = keras.layers.Dropout(0.20, name="dropout_1")(first)
        logits = keras.layers.Dense(number_of_classes, name="logits")(second)
        full_model = keras.Model(inputs, logits, name="ripenai_mobilenetv2_gpu_cached")
        feature_model = keras.Model(inputs, pooled, name="ripenai_feature_extractor")
    return full_model, feature_model


def cache_features(feature_model, loader: DataLoader) -> tuple[np.ndarray, np.ndarray]:
    all_features: list[np.ndarray] = []
    all_labels: list[np.ndarray] = []
    with torch.no_grad():
        for images, labels in loader:
            images = images.to("cuda", non_blocking=True)
            with torch.autocast(device_type="cuda", dtype=torch.float16):
                features = feature_model(images, training=False)
            all_features.append(features.float().cpu().numpy())
            all_labels.append(labels.numpy())
    return np.concatenate(all_features), np.concatenate(all_labels)


def main() -> None:
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is not available; refusing to run the GPU pipeline on CPU.")
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)
    names_list = names()
    train_loader = DataLoader(ImageDataset(PROCESSED / "train", names_list, True), batch_size=BATCH_SIZE, shuffle=False, num_workers=WORKERS, pin_memory=True, persistent_workers=WORKERS > 0)
    val_loader = DataLoader(ImageDataset(PROCESSED / "val", names_list, False), batch_size=BATCH_SIZE, shuffle=False, num_workers=WORKERS, pin_memory=True, persistent_workers=WORKERS > 0)
    model, feature_model = build_feature_model(len(names_list))
    print(f"CUDA device: {torch.cuda.get_device_name(0)}")
    print(f"Caching features: train={len(train_loader.dataset)} val={len(val_loader.dataset)} batch={BATCH_SIZE} workers={WORKERS}", flush=True)
    train_features, train_labels = cache_features(feature_model, train_loader)
    val_features, val_labels = cache_features(feature_model, val_loader)
    print(f"Cached feature matrices: train={train_features.shape} val={val_features.shape}", flush=True)

    feature_train = DataLoader(TensorDataset(torch.from_numpy(train_features), torch.from_numpy(train_labels)), batch_size=256, shuffle=True)
    feature_val = DataLoader(TensorDataset(torch.from_numpy(val_features), torch.from_numpy(val_labels)), batch_size=512, shuffle=False)
    head = nn.Sequential(nn.Linear(train_features.shape[1], 128), nn.ReLU(), nn.Dropout(0.30), nn.Linear(128, len(names_list))).cuda()
    counts = np.bincount(train_labels, minlength=len(names_list)).astype(np.float32)
    weights = torch.tensor(counts.sum() / np.maximum(counts * len(names_list), 1.0), device="cuda")
    optimizer = torch.optim.Adam(head.parameters(), lr=1e-3)
    best_accuracy = -1.0
    best_state = None
    history: list[dict] = []
    for epoch in range(HEAD_EPOCHS):
        head.train()
        train_correct = train_total = 0
        train_loss = 0.0
        for features, labels in feature_train:
            features, labels = features.cuda(non_blocking=True), labels.cuda(non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            logits = head(features)
            loss = nn.functional.cross_entropy(logits, labels, weight=weights)
            loss.backward()
            optimizer.step()
            train_loss += float(loss.item()) * labels.size(0)
            train_correct += int((logits.argmax(1) == labels).sum().item())
            train_total += labels.size(0)
        head.eval()
        val_correct = val_total = 0
        val_loss = 0.0
        with torch.no_grad():
            for features, labels in feature_val:
                features, labels = features.cuda(non_blocking=True), labels.cuda(non_blocking=True)
                logits = head(features)
                loss = nn.functional.cross_entropy(logits, labels, weight=weights)
                val_loss += float(loss.item()) * labels.size(0)
                val_correct += int((logits.argmax(1) == labels).sum().item())
                val_total += labels.size(0)
        record = {"epoch": epoch + 1, "train_loss": train_loss / train_total, "train_accuracy": train_correct / train_total, "val_loss": val_loss / val_total, "val_accuracy": val_correct / val_total}
        history.append(record)
        print(json.dumps(record), flush=True)
        if record["val_accuracy"] > best_accuracy:
            best_accuracy = record["val_accuracy"]
            best_state = {key: value.detach().cpu().clone() for key, value in head.state_dict().items()}

    if best_state is None:
        raise RuntimeError("No best head state was produced")
    head.load_state_dict(best_state)
    dense_layers = [layer for layer in model.layers if isinstance(layer, keras.layers.Dense)]
    dense_layers[0].kernel.assign(head[0].weight.detach().cpu().numpy().T)
    dense_layers[0].bias.assign(head[0].bias.detach().cpu().numpy())
    dense_layers[1].kernel.assign(head[3].weight.detach().cpu().numpy().T)
    dense_layers[1].bias.assign(head[3].bias.detach().cpu().numpy())
    candidate = OUTPUTS / "gpu_cached_candidate.keras"
    model.save(candidate)
    (OUTPUTS / "gpu_cached_history.json").write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")
    (OUTPUTS / "gpu_cached_labels.json").write_text(json.dumps({str(i): name for i, name in enumerate(names_list)}, indent=2) + "\n", encoding="utf-8")
    print(f"Best validation accuracy: {best_accuracy:.4f}")
    print(f"Saved: {candidate}")


if __name__ == "__main__":
    main()
