# External datasets

## BananaImageBD ripeness classification

- Source: <https://huggingface.co/datasets/Project-AgML/BananaImageBD_ripeness_classification>
- Downloaded file: `bananaimagebd/raw/train-00000-of-00001.parquet`
- Original distribution: 820 images — Green 212, Overripe 203, Ripe 201, Semi-ripe 204.
- Use: banana-specific robustness/evaluation and future retraining.
- The current extractor maps `Green -> unripe`, `Overripe -> overripe`, and
  `Ripe/Semi-ripe -> ripe`; the original integer label is preserved in
  `bananaimagebd/extracted/manifest.csv`.
- The dataset page provides the citation and points to the BananaImageBD
  publication/Mendeley record. Verify any downstream redistribution terms
  before shipping the dataset outside this workspace.

The source is intentionally kept separate from `data/processed` until the
split policy and four-state label contract are reviewed. This prevents
augmented copies or source-specific backgrounds from leaking into the test
set.

## Rotten safety detector

The processed pipeline preserves explicit `rotten` labels from RipeNet and the
Leftin source instead of merging them into `overripe`. The Android build uses
those samples for a separate binary safety detector so the stronger
three-stage ripeness model is not degraded by a sparse four-stage head.
