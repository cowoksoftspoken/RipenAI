"""Native CUDA MobileNetV2 training and ONNX export for RipenAI."""

from __future__ import annotations

import json
import os
import random
from pathlib import Path

import numpy as np
import torch
from PIL import ImageFile
from torch import nn
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms

ImageFile.LOAD_TRUNCATED_IMAGES = True
PROJECT = Path(__file__).resolve().parents[1]
DATA = PROJECT / "data" / "processed"
OUTPUTS = PROJECT / "outputs"
BATCH_SIZE = int(os.environ.get("RIPEN_NATIVE_BATCH_SIZE", "16"))
WORKERS = int(os.environ.get("RIPEN_NATIVE_WORKERS", "2"))
HEAD_EPOCHS = int(os.environ.get("RIPEN_NATIVE_HEAD_EPOCHS", "4"))
FINE_EPOCHS = int(os.environ.get("RIPEN_NATIVE_FINE_EPOCHS", "1"))
SEED = 42


def seed() -> None:
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)
    torch.cuda.manual_seed_all(SEED)


class RipenNet(nn.Module):
    def __init__(self, number_of_classes: int, pretrained: bool = True) -> None:
        super().__init__()
        weights = models.MobileNet_V2_Weights.DEFAULT if pretrained else None
        self.backbone = models.mobilenet_v2(weights=weights)
        self.backbone.classifier = nn.Sequential(
            nn.Dropout(0.30),
            nn.Linear(1280, 128),
            nn.ReLU(inplace=True),
            nn.Dropout(0.20),
            nn.Linear(128, number_of_classes),
        )
        self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))

    def forward(self, image_01: torch.Tensor) -> torch.Tensor:
        return self.backbone((image_01 - self.mean) / self.std)


class RawNhwcExport(nn.Module):
    def __init__(self, model: RipenNet) -> None:
        super().__init__()
        self.model = model

    def forward(self, raw_nhwc: torch.Tensor) -> torch.Tensor:
        image_01 = raw_nhwc.permute(0, 3, 1, 2) / 255.0
        return self.model(image_01)


def make_loaders() -> tuple[DataLoader, DataLoader, list[str], dict[int, float]]:
    train_transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.RandomHorizontalFlip(),
        transforms.RandomRotation(8),
        transforms.ColorJitter(brightness=0.15, contrast=0.15),
        transforms.ToTensor(),
    ])
    eval_transform = transforms.Compose([transforms.Resize((224, 224)), transforms.ToTensor()])
    train = datasets.ImageFolder(DATA / "train", transform=train_transform)
    val = datasets.ImageFolder(DATA / "val", transform=eval_transform)
    counts = np.bincount(train.targets, minlength=len(train.classes)).astype(np.float32)
    class_weights = {index: float(counts.sum() / max(count * len(train.classes), 1.0)) for index, count in enumerate(counts)}
    options = {"batch_size": BATCH_SIZE, "num_workers": WORKERS, "pin_memory": True, "persistent_workers": WORKERS > 0}
    return DataLoader(train, shuffle=True, **options), DataLoader(val, shuffle=False, **options), train.classes, class_weights


def epoch(model, loader, optimizer, criterion, scaler, training: bool) -> tuple[float, float]:
    model.train(training)
    total_loss = correct = total = 0
    for images, labels in loader:
        images = images.cuda(non_blocking=True)
        labels = labels.cuda(non_blocking=True)
        if training:
            optimizer.zero_grad(set_to_none=True)
        with torch.amp.autocast("cuda", dtype=torch.float16):
            logits = model(images)
            loss = criterion(logits, labels)
        if training:
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
        total_loss += float(loss.detach().item()) * labels.size(0)
        correct += int((logits.argmax(1) == labels).sum().item())
        total += labels.size(0)
    return total_loss / max(total, 1), correct / max(total, 1)


def main() -> None:
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; refusing to train this candidate on CPU.")
    seed()
    torch.set_float32_matmul_precision("high")
    train_loader, val_loader, classes, weight_map = make_loaders()
    model = RipenNet(len(classes)).cuda()
    for parameter in model.backbone.features.parameters():
        parameter.requires_grad = False
    weights = torch.tensor([weight_map[index] for index in range(len(classes))], device="cuda")
    criterion = nn.CrossEntropyLoss(weight=weights)
    scaler = torch.amp.GradScaler("cuda")
    optimizer = torch.optim.Adam(model.backbone.classifier.parameters(), lr=1e-3)
    best_accuracy = -1.0
    best_state = None
    history: list[dict] = []
    print(f"CUDA device: {torch.cuda.get_device_name(0)}")
    print(f"Dataset: train={len(train_loader.dataset)} val={len(val_loader.dataset)} classes={len(classes)} batch={BATCH_SIZE} workers={WORKERS}", flush=True)
    for phase, epochs in (("head", HEAD_EPOCHS), ("fine_tune", FINE_EPOCHS)):
        if phase == "fine_tune":
            for layer in model.backbone.features[-4:]:
                layer.trainable = True
                for parameter in layer.parameters():
                    parameter.requires_grad = True
            optimizer = torch.optim.Adam((parameter for parameter in model.parameters() if parameter.requires_grad), lr=2e-5)
        for index in range(epochs):
            train_loss, train_accuracy = epoch(model, train_loader, optimizer, criterion, scaler, True)
            val_loss, val_accuracy = epoch(model, val_loader, optimizer, criterion, scaler, False)
            record = {"phase": phase, "epoch": index + 1, "train_loss": train_loss, "train_accuracy": train_accuracy, "val_loss": val_loss, "val_accuracy": val_accuracy}
            history.append(record)
            print(json.dumps(record), flush=True)
            if val_accuracy > best_accuracy:
                best_accuracy = val_accuracy
                best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}

    if best_state is None:
        raise RuntimeError("No model checkpoint was produced")
    model.load_state_dict(best_state)
    OUTPUTS.mkdir(parents=True, exist_ok=True)
    checkpoint = OUTPUTS / "native_cuda_candidate.pt"
    torch.save({"state_dict": model.state_dict(), "classes": classes, "history": history}, checkpoint)
    wrapper = RawNhwcExport(model).eval().cuda()
    onnx_path = OUTPUTS / "native_cuda_candidate.onnx"
    dummy = torch.zeros(1, 224, 224, 3, device="cuda")
    torch.onnx.export(wrapper, dummy, onnx_path, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
    (OUTPUTS / "native_cuda_history.json").write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")
    (OUTPUTS / "native_cuda_labels.json").write_text(json.dumps({str(i): value for i, value in enumerate(classes)}, indent=2) + "\n", encoding="utf-8")
    print(f"Best validation accuracy: {best_accuracy:.4f}")
    print(f"Saved checkpoint: {checkpoint}")
    print(f"Saved ONNX: {onnx_path}")


if __name__ == "__main__":
    main()
