"""Train the RipenAI head with Keras 3's PyTorch backend on CUDA.

TensorFlow 2.21 on native Windows is CPU-only, while this workstation has a
working CUDA PyTorch installation. Keras 3 lets us keep the MobileNetV2 model
contract and use the PyTorch backend for the training step, so the exported
model can still be converted to the TFLite asset consumed by Android.
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
import torch.nn.functional as F
from PIL import Image, ImageEnhance, ImageOps
from torch.utils.data import DataLoader, Dataset


PROJECT = Path(__file__).resolve().parents[1]
PROCESSED = PROJECT / "data" / "processed"
OUTPUTS = PROJECT / "outputs"
IMAGE_SIZE = 224
SEED = 42
BATCH_SIZE = int(os.environ.get("RIPEN_GPU_BATCH_SIZE", "16"))
NUM_WORKERS = int(os.environ.get("RIPEN_GPU_WORKERS", "2"))
HEAD_EPOCHS = int(os.environ.get("RIPEN_GPU_HEAD_EPOCHS", "4"))
FINE_TUNE_EPOCHS = int(os.environ.get("RIPEN_GPU_FINE_TUNE_EPOCHS", "2"))


def seed_everything() -> None:
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)
    torch.cuda.manual_seed_all(SEED)


class FruitDataset(Dataset):
    def __init__(self, root: Path, class_names: list[str], training: bool) -> None:
        self.training = training
        self.class_to_index = {name: index for index, name in enumerate(class_names)}
        self.items = [
            (path, self.class_to_index[path.parent.name])
            for path in sorted(root.glob("*/*"))
            if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
        ]

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, int]:
        path, label = self.items[index]
        with Image.open(path) as source:
            image = ImageOps.fit(source.convert("RGB"), (IMAGE_SIZE, IMAGE_SIZE), method=Image.Resampling.BILINEAR)
        if self.training:
            if random.random() < 0.5:
                image = ImageOps.mirror(image)
            if random.random() < 0.25:
                image = image.rotate(random.uniform(-8.0, 8.0), resample=Image.Resampling.BILINEAR, fillcolor=(255, 255, 255))
            if random.random() < 0.35:
                image = ImageEnhance.Brightness(image).enhance(random.uniform(0.88, 1.12))
            if random.random() < 0.35:
                image = ImageEnhance.Contrast(image).enhance(random.uniform(0.88, 1.12))
        array = np.asarray(image, dtype=np.float32)
        return torch.from_numpy(array), label


def class_names() -> list[str]:
    return sorted(path.name for path in (PROCESSED / "train").iterdir() if path.is_dir())


def build_model(number_of_classes: int):
    with keras.device("cuda"):
        inputs = keras.Input(shape=(IMAGE_SIZE, IMAGE_SIZE, 3), name="image")
        x = keras.applications.mobilenet_v2.preprocess_input(inputs)
        base = keras.applications.MobileNetV2(
            weights="imagenet",
            include_top=False,
            input_shape=(IMAGE_SIZE, IMAGE_SIZE, 3),
        )
        base.trainable = False
        x = base(x, training=False)
        x = keras.layers.GlobalAveragePooling2D()(x)
        x = keras.layers.Dropout(0.30)(x)
        x = keras.layers.Dense(128, activation="relu")(x)
        x = keras.layers.Dropout(0.20)(x)
        outputs = keras.layers.Dense(number_of_classes, name="logits")(x)
        model = keras.Model(inputs, outputs, name="ripenai_mobilenetv2_gpu")
    return model, base


def class_weights(dataset: FruitDataset, count: int) -> torch.Tensor:
    counts = np.bincount([label for _, label in dataset.items], minlength=count).astype(np.float32)
    weights = counts.sum() / np.maximum(counts * count, 1.0)
    return torch.tensor(weights, dtype=torch.float32, device="cuda")


def run_epoch(model, loader, optimizer, weights, training: bool) -> tuple[float, float]:
    model.train(training)
    total_loss = 0.0
    correct = 0
    total = 0
    for images, labels in loader:
        images = images.to("cuda", non_blocking=True)
        labels = labels.to("cuda", non_blocking=True)
        if training:
            optimizer.zero_grad(set_to_none=True)
        with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=True):
            logits = model(images, training=training)
            loss = F.cross_entropy(logits, labels, weight=weights)
        if training:
            loss.backward()
            optimizer.step()
        predictions = logits.argmax(dim=1)
        total_loss += float(loss.detach().item()) * labels.size(0)
        correct += int((predictions == labels).sum().item())
        total += labels.size(0)
    return total_loss / max(total, 1), correct / max(total, 1)


def main() -> None:
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is not available to PyTorch; refusing to claim GPU training.")
    seed_everything()
    names = class_names()
    train_set = FruitDataset(PROCESSED / "train", names, training=True)
    val_set = FruitDataset(PROCESSED / "val", names, training=False)
    loader_options = {
        "batch_size": BATCH_SIZE,
        "num_workers": NUM_WORKERS,
        "pin_memory": True,
        "persistent_workers": NUM_WORKERS > 0,
    }
    train_loader = DataLoader(train_set, shuffle=True, **loader_options)
    val_loader = DataLoader(val_set, shuffle=False, **loader_options)
    model, base = build_model(len(names))
    trainable = [variable.value for variable in model.trainable_variables]
    weights = class_weights(train_set, len(names))
    optimizer = torch.optim.Adam(trainable, lr=1e-3)
    best_accuracy = -1.0
    best_path = OUTPUTS / "gpu_candidate_best.keras"
    history: list[dict] = []
    print(f"CUDA device: {torch.cuda.get_device_name(0)}")
    print(f"Dataset: train={len(train_set)} val={len(val_set)} classes={len(names)} batch={BATCH_SIZE} workers={NUM_WORKERS}", flush=True)

    for epoch in range(HEAD_EPOCHS):
        train_loss, train_accuracy = run_epoch(model, train_loader, optimizer, weights, training=True)
        val_loss, val_accuracy = run_epoch(model, val_loader, optimizer, weights, training=False)
        record = {"phase": "head", "epoch": epoch + 1, "train_loss": train_loss, "train_accuracy": train_accuracy, "val_loss": val_loss, "val_accuracy": val_accuracy}
        history.append(record)
        print(json.dumps(record), flush=True)
        if val_accuracy > best_accuracy:
            best_accuracy = val_accuracy
            model.save(best_path)

    # Fine-tune the last MobileNet blocks while keeping BatchNorm frozen.
    base.trainable = True
    for layer in base.layers[:-10]:
        layer.trainable = False
    for layer in base.layers:
        if isinstance(layer, keras.layers.BatchNormalization):
            layer.trainable = False
    optimizer = torch.optim.Adam([variable.value for variable in model.trainable_variables], lr=2e-5)
    for epoch in range(FINE_TUNE_EPOCHS):
        train_loss, train_accuracy = run_epoch(model, train_loader, optimizer, weights, training=True)
        val_loss, val_accuracy = run_epoch(model, val_loader, optimizer, weights, training=False)
        record = {"phase": "fine_tune", "epoch": epoch + 1, "train_loss": train_loss, "train_accuracy": train_accuracy, "val_loss": val_loss, "val_accuracy": val_accuracy}
        history.append(record)
        print(json.dumps(record), flush=True)
        if val_accuracy > best_accuracy:
            best_accuracy = val_accuracy
            model.save(best_path)

    OUTPUTS.mkdir(parents=True, exist_ok=True)
    (OUTPUTS / "gpu_candidate_history.json").write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")
    (OUTPUTS / "gpu_candidate_labels.json").write_text(json.dumps({str(i): name for i, name in enumerate(names)}, indent=2) + "\n", encoding="utf-8")
    print(f"Best validation accuracy: {best_accuracy:.4f}")
    print(f"Saved: {best_path}")


if __name__ == "__main__":
    main()
