"""Fast augmentation retraining for CPU-only environments.

The frozen MobileNetV2 backbone is run once on an augmented view of each
training image. The small classifier head is then trained on cached features,
which keeps the experiment reproducible without spending hours backpropagating
through the frozen backbone.
"""

from __future__ import annotations

import json
import os
import pickle
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.utils.class_weight import compute_class_weight

import train_model


PROJECT = Path(__file__).resolve().parents[1]
PROCESSED = PROJECT / "data" / "processed"
OUTPUTS = PROJECT / "outputs"
IMG_SIZE = (224, 224)
BATCH_SIZE = int(os.environ.get("RIPEN_CACHE_BATCH_SIZE", "128"))
HEAD_EPOCHS = int(os.environ.get("RIPEN_HEAD_EPOCHS", "8"))


def set_threads() -> None:
    threads = int(os.environ.get("RIPEN_CPU_THREADS", "0"))
    if threads > 0:
        tf.config.threading.set_intra_op_parallelism_threads(threads)
        tf.config.threading.set_inter_op_parallelism_threads(max(1, threads // 2))


def class_weights(class_names: list[str]) -> dict[int, float]:
    labels: list[int] = []
    for index, name in enumerate(class_names):
        count = len(list((PROCESSED / "train" / name).glob("*.jpg")))
        labels.extend([index] * count)
    weights = compute_class_weight("balanced", classes=np.arange(len(class_names)), y=np.asarray(labels))
    return {index: float(weight) for index, weight in enumerate(weights)}


def extract_features(feature_model: tf.keras.Model, dataset: tf.data.Dataset, training: bool) -> tuple[np.ndarray, np.ndarray]:
    features: list[np.ndarray] = []
    labels: list[np.ndarray] = []
    total = 0
    for images, batch_labels in dataset:
        batch_features = feature_model(images, training=training).numpy()
        features.append(batch_features)
        labels.append(batch_labels.numpy())
        total += len(images)
        if total % 2000 < len(images):
            print(f"cached {total} images")
    return np.concatenate(features), np.concatenate(labels)


def build_head(feature_size: int, num_classes: int) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(feature_size,), name="cached_features")
    x = tf.keras.layers.Dropout(0.3, name="head_dropout_1")(inputs)
    x = tf.keras.layers.Dense(128, activation="relu", name="head_dense")(x)
    x = tf.keras.layers.Dropout(0.2, name="head_dropout_2")(x)
    outputs = tf.keras.layers.Dense(num_classes, activation="softmax", name="head_output")(x)
    return tf.keras.Model(inputs, outputs, name="ripenai_cached_head")


def main() -> None:
    set_threads()
    train_model.BATCH_SIZE = BATCH_SIZE
    print(f"Using cache batch size {BATCH_SIZE}; head epochs {HEAD_EPOCHS}")

    train_ds, val_ds, test_ds, class_names = train_model.load_datasets()
    model, _base = train_model.build_model(len(class_names), train_model.create_data_augmentation())
    feature_model = tf.keras.Model(model.input, model.get_layer("global_average_pooling2d").output)

    print("Extracting one augmented view per training image...")
    train_features, train_labels = extract_features(feature_model, train_ds, training=True)
    print("Extracting deterministic validation features...")
    val_features, val_labels = extract_features(feature_model, val_ds, training=False)

    weights = class_weights(class_names)
    head = build_head(train_features.shape[-1], len(class_names))
    head.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3), loss="categorical_crossentropy", metrics=["accuracy"])
    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor="val_accuracy", patience=3, restore_best_weights=True, verbose=1),
        tf.keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=2, min_lr=1e-6, verbose=1),
    ]
    history = head.fit(
        train_features,
        train_labels,
        validation_data=(val_features, val_labels),
        epochs=HEAD_EPOCHS,
        batch_size=512,
        class_weight=weights,
        callbacks=callbacks,
        verbose=1,
    )

    model.get_layer("dense").set_weights(head.get_layer("head_dense").get_weights())
    model.get_layer("dense_1").set_weights(head.get_layer("head_output").get_weights())
    candidate_path = OUTPUTS / "augmented_head_candidate.keras"
    model.save(candidate_path)
    with (OUTPUTS / "augmented_head_history.pkl").open("wb") as handle:
        pickle.dump(history.history, handle)
    (OUTPUTS / "augmented_head_config.json").write_text(json.dumps({
        "method": "cached_features_one_augmented_view",
        "batch_size": BATCH_SIZE,
        "head_epochs_requested": HEAD_EPOCHS,
        "classes": class_names,
    }, indent=2), encoding="utf-8")
    print(f"candidate saved to {candidate_path}")


if __name__ == "__main__":
    main()
