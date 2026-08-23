"""Download the four Kaggle sources used by the multi-fruit pipeline.

Kaggle CLI can resume large public archives. Extraction is kept in separate
directories because the preparation script knows each source's label layout.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[1]
RAW_DIR = PROJECT_DIR / "data" / "raw"
DATASETS = {
    "leftin": "leftin/fruit-ripeness-unripe-ripe-and-rotten",
    "asadullahprl_clean": "asadullahprl/fruits-ripeness-classification-dataset",
    "amldvvs_clean": "amldvvs/avocado-ripeness-classification-dataset",
    "alexcj10_clean": "alexcj10/ripenet-2-0-fruit-dataset",
}


def download(slug: str, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    subprocess.run(["kaggle", "datasets", "download", slug, "-p", str(target)], check=True, timeout=7200)
    archives = sorted(target.glob("*.zip"), key=lambda path: path.stat().st_mtime, reverse=True)
    if not archives:
        raise RuntimeError(f"No archive found for {slug}")
    archive = archives[0]
    extract_to = target / "extracted"
    extract_to.mkdir(parents=True, exist_ok=True)
    print(f"Extracting {archive.name} -> {extract_to}")
    with zipfile.ZipFile(archive) as handle:
        handle.extractall(extract_to)


def copy_ripenet_master_label() -> None:
    source_root = RAW_DIR / "alexcj10_clean" / "extracted"
    labels = list(source_root.rglob("ripenet_v2_master.csv"))
    if not labels:
        print("[warning] RipeNet master CSV was not found after extraction")
        return
    destination = RAW_DIR / "alexcj10_labels"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(labels[0], destination / labels[0].name)


def main() -> None:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    failures: list[str] = []
    for name, slug in DATASETS.items():
        try:
            print(f"\n=== {slug} ===")
            download(slug, RAW_DIR / name)
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            print(f"[failed] {slug}: {error}")
            failures.append(slug)
    copy_ripenet_master_label()
    if failures:
        print("\nSome downloads failed. Re-run this script to resume them:")
        for failure in failures:
            print(f"  - {failure}")
        sys.exit(1)
    print(f"\nAll sources are available under {RAW_DIR}")


if __name__ == "__main__":
    main()
