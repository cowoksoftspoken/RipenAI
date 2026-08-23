"""Prepare public RipenAI fruit datasets for multi-fruit training.

The previous pipeline fabricated missing classes with colour shifts. This
pipeline keeps only labels that exist in source data and normalises them to
the app vocabulary: unripe, ripe, overripe, and rotten. The app can still
understand nearly_ripe when a future labelled source is added, but this script
never invents that label.

Expected optional inputs under data/raw:
  - asadullahprl_clean/extracted (folder-labelled images)
  - alexcj10_clean/extracted (RipeNet 2.0 images)
  - alexcj10_labels/ripenet_v2_master.csv (RipeNet labels)
  - leftin (folder-labelled images, when downloaded)

Outputs:
  - data/processed/{train,val,test}/{fruit}_{stage}/*.jpg
  - outputs/class_labels.json, fruit_catalog.json, ripeness_days.json
  - outputs/dataset_manifest.json
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import random
import re
import shutil
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps


PROJECT_DIR = Path(__file__).resolve().parents[1]
RAW_DIR = PROJECT_DIR / "data" / "raw"
EXTERNAL_DIR = PROJECT_DIR / "data" / "raw_external"
PROCESSED_DIR = PROJECT_DIR / "data" / "processed"
OUTPUTS_DIR = PROJECT_DIR / "outputs"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".gif", ".tif", ".tiff"}
STAGES = ("unripe", "ripe", "overripe", "rotten")
SEED = 42
MIN_IMAGES_PER_CLASS = 18


@dataclass(frozen=True)
class Sample:
    path: Path
    fruit: str
    stage: str
    source: str
    source_split: str | None = None


FRUIT_ALIASES = {
    "apple": "apple", "apples": "apple", "banana": "banana", "bananas": "banana",
    "mango": "mango", "mangoes": "mango", "mangos": "mango", "orange": "orange",
    "oranges": "orange", "tomato": "tomato", "tomatoes": "tomato", "tomat": "tomato",
    "papaya": "papaya", "papayas": "papaya", "pineapple": "pineapple",
    "pineapples": "pineapple", "avocado": "avocado",
}

STAGE_ALIASES = {
    "unripe": "unripe", "underripe": "unripe", "under-ripe": "unripe", "raw": "unripe",
    "immature": "unripe", "green": "unripe", "hard": "unripe", "ripe": "ripe",
    "fresh": "ripe", "mature": "ripe", "good": "ripe", "ready": "ripe",
    "overripe": "overripe", "over-ripe": "overripe", "overipe": "overripe",
    "rotten": "rotten", "spoiled": "rotten", "spoilt": "rotten", "bad": "rotten",
    "decayed": "rotten", "decay": "rotten",
}


def normalise(value: str) -> str:
    value = value.strip().lower().replace("_", "-")
    return re.sub(r"[^a-z0-9-]+", "-", value).strip("-")


def tokenise(value: str) -> list[str]:
    return [token for token in re.split(r"[^a-z0-9]+", value.lower()) if token]


def find_fruit(parts: list[str]) -> str | None:
    joined = "-".join(normalise(part) for part in parts)
    for alias, fruit in sorted(FRUIT_ALIASES.items(), key=lambda item: -len(item[0])):
        if alias in joined:
            return fruit
    for part in parts:
        for token in tokenise(part):
            if token in FRUIT_ALIASES:
                return FRUIT_ALIASES[token]
    return None


def find_stage(parts: list[str]) -> str | None:
    joined = "-".join(normalise(part) for part in parts)
    for alias, stage in sorted(STAGE_ALIASES.items(), key=lambda item: -len(item[0])):
        if alias in joined:
            return stage
    for part in parts:
        for token in tokenise(part):
            if token in STAGE_ALIASES:
                return STAGE_ALIASES[token]
    return None


def is_image(path: Path) -> bool:
    return path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS


def scan_folder_dataset(root: Path, source: str) -> list[Sample]:
    samples: list[Sample] = []
    if not root.exists():
        return samples
    for path in root.rglob("*"):
        if not is_image(path):
            continue
        relative_parts = list(path.relative_to(root).parts)
        fruit = find_fruit(relative_parts)
        stage = find_stage(relative_parts)
        if not fruit or not stage:
            continue
        source_split = next(
            (part.lower() for part in relative_parts if part.lower() in {"train", "test", "val", "valid", "validation"}),
            None,
        )
        if source_split == "validation":
            source_split = "val"
        samples.append(Sample(path, fruit, stage, source, source_split))
    return samples


def find_ripenet_root() -> Path | None:
    for candidate in (RAW_DIR / "alexcj10_clean", RAW_DIR / "alexcj10"):
        if not candidate.exists():
            continue
        for path in candidate.rglob("*"):
            if path.is_dir() and path.name.lower() == "ripenet 2.0":
                return path
    return None


def scan_ripenet() -> list[Sample]:
    labels_candidates = list((RAW_DIR / "alexcj10_labels").glob("*.csv")) + list((RAW_DIR / "alexcj10").glob("*.csv"))
    image_root = find_ripenet_root()
    if not labels_candidates or image_root is None:
        return []
    samples: list[Sample] = []
    with labels_candidates[0].open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            fruit = find_fruit([row.get("fruit", "")])
            stage = find_stage([row.get("stage", "")])
            relative = (row.get("relative_path") or "").replace("\\", os.sep).replace("/", os.sep)
            image_path = image_root / relative
            if fruit and stage and is_image(image_path):
                samples.append(Sample(image_path, fruit, stage, "alexcj10-ripenet"))
    return samples


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def deduplicate(samples: list[Sample]) -> list[Sample]:
    seen: set[str] = set()
    output: list[Sample] = []
    for sample in samples:
        try:
            key = sha256(sample.path)
        except OSError:
            continue
        if key not in seen:
            seen.add(key)
            output.append(sample)
    return output


def assign_splits(samples: list[Sample]) -> dict[str, list[Sample]]:
    grouped: dict[str, list[Sample]] = defaultdict(list)
    for sample in samples:
        grouped[f"{sample.fruit}_{sample.stage}"].append(sample)
    rng = random.Random(SEED)
    splits: dict[str, list[Sample]] = {name: [] for name in ("train", "val", "test")}
    for class_name, items in sorted(grouped.items()):
        rng.shuffle(items)
        total = len(items)
        train_end = max(1, int(total * 0.75))
        val_end = min(total - 1, train_end + max(1, int(total * 0.15)))
        split_items = {"train": items[:train_end], "val": items[train_end:val_end], "test": items[val_end:]}
        for split_name, split_samples in split_items.items():
            splits[split_name].extend(split_samples)
        print(f"  {class_name:24s} total={total:5d} train={len(split_items['train']):4d} val={len(split_items['val']):4d} test={len(split_items['test']):4d}")
    return splits


def save_image(source: Path, destination: Path) -> bool:
    try:
        with Image.open(source) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            image.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
            destination.parent.mkdir(parents=True, exist_ok=True)
            image.save(destination, format="JPEG", quality=94, optimize=True)
        return True
    except Exception as error:  # noqa: BLE001
        print(f"  [skip] {source}: {error}")
        return False


def find_avocado_csv() -> bool:
    return any((RAW_DIR / "amldvvs_clean").glob("*.csv"))


def source_status() -> dict[str, bool]:
    return {
        "asadullahprl": (RAW_DIR / "asadullahprl_clean" / "extracted").exists(),
        "alexcj10-ripenet": find_ripenet_root() is not None,
        "leftin": (RAW_DIR / "leftin").exists() and any((RAW_DIR / "leftin").rglob("*")),
        "bananaimagebd": (EXTERNAL_DIR / "bananaimagebd" / "extracted").exists()
        and any((EXTERNAL_DIR / "bananaimagebd" / "extracted").rglob("*.jpg")),
        "amldvvs-avocado-tabular": find_avocado_csv(),
    }


def write_metadata(class_names: list[str], samples: list[Sample], split_counts: dict[str, Counter]) -> None:
    OUTPUTS_DIR.mkdir(parents=True, exist_ok=True)
    labels = {str(index): label for index, label in enumerate(class_names)}
    (OUTPUTS_DIR / "class_labels.json").write_text(json.dumps(labels, indent=2) + "\n", encoding="utf-8")
    labels_by_fruit = defaultdict(list)
    for label in class_names:
        fruit, stage = label.rsplit("_", 1)
        labels_by_fruit[fruit].append(stage)
    display_names = {
        "apple": "Apel", "banana": "Pisang", "mango": "Mangga", "orange": "Jeruk",
        "papaya": "Pepaya", "pineapple": "Nanas", "tomato": "Tomat", "avocado": "Alpukat",
    }
    catalog = [{"id": fruit, "label": display_names.get(fruit, fruit.title()), "stages": sorted(stages), "visual_model": True} for fruit, stages in sorted(labels_by_fruit.items())]
    # The supplied avocado source is tabular (firmness/colour/sound), not images.
    # Keep it available in the consumer selector, but mark it honestly as a
    # question-guided path instead of pretending it trained the vision model.
    if find_avocado_csv() and not any(item["id"] == "avocado" for item in catalog):
        catalog.append({"id": "avocado", "label": "Alpukat", "stages": list(STAGES), "visual_model": False, "question_only": True})
        catalog.sort(key=lambda item: item["id"])
    (OUTPUTS_DIR / "fruit_catalog.json").write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    days = {fruit: {stage: (5 if stage == "unripe" else 0 if stage == "ripe" else -1) for stage in stages} for fruit, stages in labels_by_fruit.items()}
    (OUTPUTS_DIR / "ripeness_days.json").write_text(json.dumps(days, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "canonical_stages": list(STAGES), "class_count": len(class_names), "classes": class_names,
        "sources": source_status(), "sample_count": len(samples),
        "prepared_sample_count": sum(sum(counter.values()) for counter in split_counts.values()),
        "seed": SEED, "synthetic_labels": False,
        "source_licenses": {
            "leftin/fruit-ripeness-unripe-ripe-and-rotten": "CC-BY-SA-4.0; retain attribution and share-alike terms",
            "asadullahprl/fruits-ripeness-classification-dataset": "CC0-1.0",
            "amldvvs/avocado-ripeness-classification-dataset": "Apache-2.0; tabular synthetic data, not used for vision training",
            "alexcj10/ripenet-2-0-fruit-dataset": "MIT",
            "Project-AgML/BananaImageBD_ripeness_classification": "Dataset card/public source; retain citation and verify redistribution terms",
        },
        "counts": {split: dict(counter) for split, counter in split_counts.items()},
        "question_only_fruits": [item["id"] for item in catalog if item.get("question_only")],
    }
    (OUTPUTS_DIR / "dataset_manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> None:
    print("RipenAI - multi-fruit dataset preparation")
    print("Sources:", source_status())
    samples = []
    samples.extend(scan_folder_dataset(RAW_DIR / "asadullahprl_clean" / "extracted", "asadullahprl"))
    samples.extend(scan_folder_dataset(RAW_DIR / "leftin", "leftin"))
    samples.extend(scan_folder_dataset(EXTERNAL_DIR / "bananaimagebd" / "extracted", "bananaimagebd"))
    samples.extend(scan_ripenet())
    if not samples:
        print("No labelled images found. Download/extract an image dataset first.")
        sys.exit(1)
    samples = deduplicate(samples)
    raw_counts = Counter(f"{sample.fruit}_{sample.stage}" for sample in samples)
    allowed = {key for key, count in raw_counts.items() if count >= MIN_IMAGES_PER_CLASS}
    samples = [sample for sample in samples if f"{sample.fruit}_{sample.stage}" in allowed]
    print(f"Unique labelled images: {len(samples)} | classes: {len(allowed)}")
    print("Raw class distribution:", dict(sorted(Counter(f"{sample.fruit}_{sample.stage}" for sample in samples).items())))
    splits = assign_splits(samples)
    if PROCESSED_DIR.exists():
        shutil.rmtree(PROCESSED_DIR)
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    split_counts: dict[str, Counter] = {}
    for split_name, split_samples in splits.items():
        split_counts[split_name] = Counter()
        for index, sample in enumerate(split_samples):
            class_name = f"{sample.fruit}_{sample.stage}"
            destination = PROCESSED_DIR / split_name / class_name / f"{sample.source}_{index:07d}.jpg"
            if save_image(sample.path, destination):
                split_counts[split_name][class_name] += 1
    class_names = sorted(
        class_name for class_name in allowed
        if split_counts["train"].get(class_name, 0) > 0
        and split_counts["val"].get(class_name, 0) > 0
        and split_counts["test"].get(class_name, 0) > 0
    )
    write_metadata(class_names, samples, split_counts)
    print(f"Prepared {sum(sum(counter.values()) for counter in split_counts.values())} images in {PROCESSED_DIR}")
    print(f"Classes written to {OUTPUTS_DIR / 'class_labels.json'}")


if __name__ == "__main__":
    main()
