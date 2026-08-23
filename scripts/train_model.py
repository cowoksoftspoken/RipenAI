"""
RipenAI - Step 3: Train MobileNetV2
2-phase transfer learning:
  Phase 1: Frozen base, train head
  Phase 2: Fine-tune top 30 layers

Optimized for MX450 GPU (2GB VRAM) with small batch size.
"""

import os
import sys
import json
import pickle
import numpy as np
import tensorflow as tf
from sklearn.utils.class_weight import compute_class_weight

# Project paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
PROCESSED_DIR = os.path.join(PROJECT_DIR, "data", "processed")
OUTPUTS_DIR = os.path.join(PROJECT_DIR, "outputs")

# Training config — tuned for MX450 2GB VRAM
IMG_SIZE = (224, 224)
BATCH_SIZE = int(os.environ.get("RIPEN_BATCH_SIZE", "16"))
PHASE1_EPOCHS = int(os.environ.get("RIPEN_PHASE1_EPOCHS", "5"))
PHASE2_EPOCHS = int(os.environ.get("RIPEN_PHASE2_EPOCHS", "8"))
PHASE1_LR = 1e-3
PHASE2_LR = 1e-5


def configure_gpu():
    """Configure GPU memory growth to avoid OOM on small GPUs."""
    gpus = tf.config.list_physical_devices('GPU')
    if gpus:
        try:
            for gpu in gpus:
                tf.config.experimental.set_memory_growth(gpu, True)
            print(f"[OK] GPU detected: {gpus[0].name}")
            print(f"  Memory growth enabled (important for MX450)")
        except RuntimeError as e:
            print(f"[Warning] GPU config error: {e}")
    else:
        print("[Warning] No GPU detected, training on CPU (will be slow)")


def create_data_augmentation():
    """Create data augmentation pipeline."""
    return tf.keras.Sequential([
        # Fruit can be photographed from either side, but vertical flips and
        # very large rotations create cues that are unlikely at market time.
        tf.keras.layers.RandomFlip("horizontal"),
        tf.keras.layers.RandomRotation(0.08),
        tf.keras.layers.RandomTranslation(0.05, 0.05),
        tf.keras.layers.RandomZoom(0.15),
        tf.keras.layers.RandomBrightness(0.15),
        tf.keras.layers.RandomContrast(0.15),
    ], name="data_augmentation")


def load_datasets():
    """Load train/val/test datasets from processed directory."""
    train_dir = os.path.join(PROCESSED_DIR, "train")
    val_dir = os.path.join(PROCESSED_DIR, "val")
    test_dir = os.path.join(PROCESSED_DIR, "test")

    for d in [train_dir, val_dir, test_dir]:
        if not os.path.exists(d):
            print(f"[Error] Directory not found: {d}")
            sys.exit(1)

    train_ds = tf.keras.utils.image_dataset_from_directory(
        train_dir,
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        label_mode='categorical',
        shuffle=True,
        seed=42,
    )

    val_ds = tf.keras.utils.image_dataset_from_directory(
        val_dir,
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        label_mode='categorical',
        shuffle=False,
    )

    test_ds = tf.keras.utils.image_dataset_from_directory(
        test_dir,
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        label_mode='categorical',
        shuffle=False,
    )

    class_names = train_ds.class_names
    print(f"\n  Classes ({len(class_names)}): {class_names}")

    # Performance optimization
    AUTOTUNE = tf.data.AUTOTUNE
    train_ds = train_ds.prefetch(buffer_size=AUTOTUNE)
    val_ds = val_ds.prefetch(buffer_size=AUTOTUNE)
    test_ds = test_ds.prefetch(buffer_size=AUTOTUNE)

    return train_ds, val_ds, test_ds, class_names


def compute_weights(train_dir, class_names):
    """Compute class weights to handle imbalance."""
    class_counts = []
    all_labels = []
    
    for i, class_name in enumerate(class_names):
        class_dir = os.path.join(train_dir, class_name)
        if os.path.exists(class_dir):
            count = len([f for f in os.listdir(class_dir) 
                        if os.path.isfile(os.path.join(class_dir, f))])
            class_counts.append(count)
            all_labels.extend([i] * count)
        else:
            class_counts.append(0)
    
    print(f"\n  Training class counts:")
    for name, count in zip(class_names, class_counts):
        bar = "#" * min(count // 5, 40)
        print(f"    {name:25s} {count:5d}  {bar}")
    
    if all_labels:
        weights = compute_class_weight(
            class_weight='balanced',
            classes=np.unique(all_labels),
            y=all_labels
        )
        class_weight = {i: w for i, w in enumerate(weights)}
        print(f"\n  Class weights: { {class_names[i]: f'{w:.2f}' for i, w in class_weight.items()} }")
        return class_weight
    
    return None


def build_model(num_classes, augmentation_layer):
    """Build MobileNetV2 with custom classification head."""
    # MobileNetV2 base
    base_model = tf.keras.applications.MobileNetV2(
        weights='imagenet',
        include_top=False,
        input_shape=(224, 224, 3)
    )
    base_model.trainable = False  # Freeze for Phase 1

    # Build model
    inputs = tf.keras.Input(shape=(224, 224, 3))
    
    # Augmentation (only during training)
    x = augmentation_layer(inputs)
    
    # MobileNetV2 preprocessing
    x = tf.keras.applications.mobilenet_v2.preprocess_input(x)
    
    # Base model
    x = base_model(x, training=False)
    
    # Classification head
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Dense(128, activation='relu')(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(num_classes, activation='softmax')(x)

    model = tf.keras.Model(inputs, outputs, name="ripenai_mobilenetv2")
    
    return model, base_model


def train_phase1(model, train_ds, val_ds, class_weight):
    """Phase 1: Train only the classification head."""
    print(f"\n{'='*60}")
    print("PHASE 1: Training Classification Head (base frozen)")
    print(f"{'='*60}")
    
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=PHASE1_LR),
        loss='categorical_crossentropy',
        metrics=['accuracy']
    )
    
    model.summary()
    
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor='val_accuracy',
            patience=5,
            restore_best_weights=True,
            verbose=1
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor='val_loss',
            factor=0.5,
            patience=3,
            min_lr=1e-6,
            verbose=1
        ),
    ]
    
    history1 = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=PHASE1_EPOCHS,
        class_weight=class_weight,
        callbacks=callbacks,
        verbose=1
    )
    
    return history1


def train_phase2(model, base_model, train_ds, val_ds, class_weight):
    """Phase 2: Fine-tune top 30 layers of MobileNetV2."""
    print(f"\n{'='*60}")
    print("PHASE 2: Fine-tuning Top 30 Layers")
    print(f"{'='*60}")
    
    # Unfreeze base model
    base_model.trainable = True
    
    # Freeze all layers except the top 30
    num_layers = len(base_model.layers)
    fine_tune_at = max(0, num_layers - 30)
    
    for layer in base_model.layers[:fine_tune_at]:
        layer.trainable = False
    
    trainable_count = sum(1 for layer in base_model.layers if layer.trainable)
    print(f"  Base model layers: {num_layers}")
    print(f"  Fine-tuning layers: {trainable_count} (from layer {fine_tune_at})")
    
    # Recompile with lower learning rate
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=PHASE2_LR),
        loss='categorical_crossentropy',
        metrics=['accuracy']
    )
    
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor='val_accuracy',
            patience=5,
            restore_best_weights=True,
            verbose=1
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor='val_loss',
            factor=0.5,
            patience=3,
            min_lr=1e-7,
            verbose=1
        ),
        tf.keras.callbacks.ModelCheckpoint(
            filepath=os.path.join(OUTPUTS_DIR, "best_model.keras"),
            monitor='val_accuracy',
            save_best_only=True,
            verbose=1
        ),
    ]
    
    history2 = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=PHASE2_EPOCHS,
        class_weight=class_weight,
        callbacks=callbacks,
        verbose=1
    )
    
    return history2


def merge_histories(h1, h2):
    """Merge training histories from both phases."""
    merged = {}
    for key in h1.history:
        merged[key] = h1.history[key] + h2.history[key]
    return merged


def main():
    print("=" * 60)
    print("RipenAI - MobileNetV2 Training")
    print("=" * 60)
    
    configure_gpu()
    
    # Load data
    print(f"\nLoading datasets from: {PROCESSED_DIR}")
    train_ds, val_ds, test_ds, class_names = load_datasets()
    
    # Compute class weights
    train_dir = os.path.join(PROCESSED_DIR, "train")
    class_weight = compute_weights(train_dir, class_names)
    
    # Build model
    print(f"\nBuilding MobileNetV2 model...")
    augmentation = create_data_augmentation()
    model, base_model = build_model(len(class_names), augmentation)
    
    # Phase 1: Train head
    history1 = train_phase1(model, train_ds, val_ds, class_weight)
    
    p1_acc = max(history1.history['val_accuracy'])
    print(f"\n  Phase 1 best val accuracy: {p1_acc:.4f}")
    
    # Phase 2: Fine-tune
    history2 = train_phase2(model, base_model, train_ds, val_ds, class_weight)
    
    p2_acc = max(history2.history['val_accuracy'])
    print(f"\n  Phase 2 best val accuracy: {p2_acc:.4f}")
    
    # Save final model
    os.makedirs(OUTPUTS_DIR, exist_ok=True)
    model_path = os.path.join(OUTPUTS_DIR, "ripenai_model.keras")
    model.save(model_path)
    print(f"\n[OK] Model saved to: {model_path}")
    
    # Save merged training history
    merged_history = merge_histories(history1, history2)
    history_path = os.path.join(OUTPUTS_DIR, "training_history.pkl")
    with open(history_path, 'wb') as f:
        pickle.dump(merged_history, f)
    print(f"[OK] Training history saved to: {history_path}")
    
    # Save class names order
    class_labels = {str(i): name for i, name in enumerate(class_names)}
    labels_path = os.path.join(OUTPUTS_DIR, "class_labels.json")
    with open(labels_path, 'w') as f:
        json.dump(class_labels, f, indent=2)
    print(f"[OK] Class labels saved to: {labels_path}")
    
    # Quick test evaluation
    print(f"\n{'='*60}")
    print("Quick Evaluation on Test Set:")
    print(f"{'='*60}")
    test_loss, test_acc = model.evaluate(test_ds, verbose=1)
    print(f"\n  Test Loss:     {test_loss:.4f}")
    print(f"  Test Accuracy: {test_acc:.4f}")
    
    if test_acc >= 0.85:
        print(f"\n  [OK] Target accuracy (85%) ACHIEVED: {test_acc:.1%}")
    else:
        print(f"\n  [Warning] Target accuracy (85%) not met: {test_acc:.1%}")
        print(f"    Model will still be exported. Consider more training data or epochs.")
    
    print(f"\n[OK] Training complete!")


if __name__ == "__main__":
    main()
