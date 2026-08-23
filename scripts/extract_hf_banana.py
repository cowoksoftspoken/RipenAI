"""Extract the downloaded BananaImageBD parquet into the training layout.

The source parquet stores image bytes and an integer label.  This script keeps
the original images (not the separately published augmented split) and adds a
stable source prefix so the external set can be evaluated independently.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd
from PIL import Image


LABELS = {
    0: "unripe",
    1: "overripe",
    2: "ripe",
    # Semi-ripe is kept as ripe for the current four-state app vocabulary.
    # The source label remains visible in the manifest for auditability.
    3: "ripe",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--parquet", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    frame = pd.read_parquet(args.parquet)
    if not {"image", "label"}.issubset(frame.columns):
        raise ValueError(f"Unexpected columns: {list(frame.columns)}")

    args.output.mkdir(parents=True, exist_ok=True)
    manifest_rows: list[str] = ["file,source_label,normalized_label\n"]
    for index, row in frame.iterrows():
        source_label = int(row["label"])
        normalized = LABELS[source_label]
        image_record = row["image"]
        image_bytes = image_record["bytes"] if isinstance(image_record, dict) else image_record
        destination_dir = args.output / f"banana_{normalized}"
        destination_dir.mkdir(parents=True, exist_ok=True)
        destination = destination_dir / f"bananaimagebd_{index:05d}.jpg"
        image = Image.open(__import__("io").BytesIO(image_bytes)).convert("RGB")
        image.save(destination, quality=95)
        manifest_rows.append(f"{destination.as_posix()},{source_label},{normalized}\n")

    (args.output / "manifest.csv").write_text("".join(manifest_rows), encoding="utf-8")
    print(f"Extracted {len(frame)} images to {args.output}")


if __name__ == "__main__":
    main()
