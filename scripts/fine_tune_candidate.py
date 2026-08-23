"""One conservative fine-tuning pass for the augmented cached-head candidate."""

from __future__ import annotations

import os
from pathlib import Path

import tensorflow as tf

import train_model


PROJECT = Path(__file__).resolve().parents[1]
INPUT = PROJECT / "outputs" / "augmented_head_candidate.keras"
OUTPUT = PROJECT / "outputs" / "augmented_finetuned_candidate.keras"
BATCH_SIZE = int(os.environ.get("RIPEN_FINE_BATCH_SIZE", "128"))
EPOCHS = int(os.environ.get("RIPEN_FINE_EPOCHS", "1"))


def main() -> None:
    train_model.BATCH_SIZE = BATCH_SIZE
    train_ds, val_ds, _test_ds, _class_names = train_model.load_datasets()
    model = tf.keras.models.load_model(INPUT)
    base = model.get_layer("mobilenetv2_1.00_224")
    base.trainable = True
    fine_tune_at = max(0, len(base.layers) - 10)
    for index, layer in enumerate(base.layers):
        layer.trainable = index >= fine_tune_at and not isinstance(layer, tf.keras.layers.BatchNormalization)
    print(f"Fine-tuning {sum(layer.trainable for layer in base.layers)} backbone layers")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=2e-5),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    class_weight = train_model.compute_weights(str(PROJECT / "data" / "processed" / "train"), _class_names)
    model.fit(train_ds, validation_data=val_ds, epochs=EPOCHS, class_weight=class_weight, verbose=1)
    model.save(OUTPUT)
    print(f"saved {OUTPUT}")


if __name__ == "__main__":
    main()
