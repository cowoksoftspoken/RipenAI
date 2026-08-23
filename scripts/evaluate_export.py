"""
RipenAI - Step 4: Evaluate Model, Generate Reports, Export TFLite
Combines evaluation, visualization, and TFLite export in one script.
"""

import os
import sys
import json
import pickle
import numpy as np
import tensorflow as tf
import matplotlib
matplotlib.use('Agg')  # Non-interactive backend
import matplotlib.pyplot as plt
from sklearn.metrics import confusion_matrix, classification_report

# Project paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
PROCESSED_DIR = os.path.join(PROJECT_DIR, "data", "processed")
OUTPUTS_DIR = os.path.join(PROJECT_DIR, "outputs")

IMG_SIZE = (224, 224)
BATCH_SIZE = 16


def configure_gpu():
    """Configure GPU memory growth."""
    gpus = tf.config.list_physical_devices('GPU')
    if gpus:
        for gpu in gpus:
            tf.config.experimental.set_memory_growth(gpu, True)


def load_test_dataset():
    """Load the test dataset."""
    test_dir = os.path.join(PROCESSED_DIR, "test")
    test_ds = tf.keras.utils.image_dataset_from_directory(
        test_dir,
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        label_mode='categorical',
        shuffle=False,
    )
    return test_ds, test_ds.class_names


def get_predictions(model, test_ds):
    """Get predictions and ground truth labels."""
    y_true = []
    y_pred = []
    
    for images, labels in test_ds:
        predictions = model.predict(images, verbose=0)
        y_true.extend(np.argmax(labels.numpy(), axis=1))
        y_pred.extend(np.argmax(predictions, axis=1))
    
    return np.array(y_true), np.array(y_pred)


def plot_training_report(history, y_true, y_pred, class_names, save_path):
    """Generate comprehensive training report PNG."""
    fig = plt.figure(figsize=(20, 14))
    fig.suptitle("RipenAI - Training Report", fontsize=18, fontweight='bold', y=0.98)
    
    # Use a nice style
    plt.style.use('seaborn-v0_8-whitegrid')
    
    # Colors
    train_color = '#2196F3'
    val_color = '#FF5722'
    
    # --- Subplot 1: Accuracy ---
    ax1 = fig.add_subplot(2, 2, 1)
    epochs = range(1, len(history['accuracy']) + 1)
    ax1.plot(epochs, history['accuracy'], color=train_color, linewidth=2, 
             label='Training', marker='o', markersize=3)
    ax1.plot(epochs, history['val_accuracy'], color=val_color, linewidth=2, 
             label='Validation', marker='s', markersize=3)
    ax1.set_title('Model Accuracy', fontsize=14, fontweight='bold')
    ax1.set_xlabel('Epoch')
    ax1.set_ylabel('Accuracy')
    ax1.legend(loc='lower right')
    ax1.set_ylim([0, 1.05])
    
    # Add phase boundary
    phase1_epochs = None
    if 'lr' in history:
        lrs = history['lr']
        for i in range(1, len(lrs)):
            if lrs[i] < lrs[i-1] * 0.1:  # Big LR drop = phase change
                phase1_epochs = i
                break
    
    if phase1_epochs:
        ax1.axvline(x=phase1_epochs + 0.5, color='gray', linestyle='--', alpha=0.5)
        ax1.text(phase1_epochs + 0.5, 0.05, 'Phase 2 ->', fontsize=9, alpha=0.7)
    
    # --- Subplot 2: Loss ---
    ax2 = fig.add_subplot(2, 2, 2)
    ax2.plot(epochs, history['loss'], color=train_color, linewidth=2, 
             label='Training', marker='o', markersize=3)
    ax2.plot(epochs, history['val_loss'], color=val_color, linewidth=2, 
             label='Validation', marker='s', markersize=3)
    ax2.set_title('Model Loss', fontsize=14, fontweight='bold')
    ax2.set_xlabel('Epoch')
    ax2.set_ylabel('Loss')
    ax2.legend(loc='upper right')
    
    if phase1_epochs:
        ax2.axvline(x=phase1_epochs + 0.5, color='gray', linestyle='--', alpha=0.5)
    
    # --- Subplot 3: Confusion Matrix ---
    ax3 = fig.add_subplot(2, 2, 3)
    cm = confusion_matrix(y_true, y_pred)
    cm_normalized = cm.astype('float') / cm.sum(axis=1, keepdims=True)
    
    im = ax3.imshow(cm_normalized, interpolation='nearest', cmap='Blues', 
                     vmin=0, vmax=1)
    ax3.set_title('Confusion Matrix (Normalized)', fontsize=14, fontweight='bold')
    
    # Add text annotations
    for i in range(len(class_names)):
        for j in range(len(class_names)):
            val = cm_normalized[i, j]
            color = 'white' if val > 0.5 else 'black'
            ax3.text(j, i, f'{val:.2f}', ha='center', va='center', 
                    color=color, fontsize=6)
    
    # Short labels for readability
    short_labels = [n.replace('banana_', 'B_').replace('mango_', 'M_').replace('tomato_', 'T_') 
                    for n in class_names]
    ax3.set_xticks(range(len(class_names)))
    ax3.set_xticklabels(short_labels, rotation=45, ha='right', fontsize=8)
    ax3.set_yticks(range(len(class_names)))
    ax3.set_yticklabels(short_labels, fontsize=8)
    ax3.set_xlabel('Predicted')
    ax3.set_ylabel('True')
    plt.colorbar(im, ax=ax3, fraction=0.046)
    
    # --- Subplot 4: Per-class accuracy bar chart ---
    ax4 = fig.add_subplot(2, 2, 4)
    per_class_acc = cm_normalized.diagonal()
    colors = []
    for acc in per_class_acc:
        if acc >= 0.85:
            colors.append('#4CAF50')  # Green
        elif acc >= 0.70:
            colors.append('#FF9800')  # Orange
        else:
            colors.append('#F44336')  # Red
    
    bars = ax4.barh(range(len(class_names)), per_class_acc, color=colors, edgecolor='white')
    ax4.set_yticks(range(len(class_names)))
    ax4.set_yticklabels(class_names, fontsize=8)
    ax4.set_xlabel('Accuracy')
    ax4.set_title('Per-Class Accuracy', fontsize=14, fontweight='bold')
    ax4.set_xlim([0, 1.1])
    ax4.axvline(x=0.85, color='gray', linestyle='--', alpha=0.5, label='85% target')
    
    # Add value labels
    for bar, acc in zip(bars, per_class_acc):
        ax4.text(acc + 0.02, bar.get_y() + bar.get_height()/2,
                f'{acc:.0%}', va='center', fontsize=8)
    
    plt.tight_layout(rect=[0, 0, 1, 0.95])
    plt.savefig(save_path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close()
    print(f"[OK] Training report saved to: {save_path}")


def export_tflite(model_path, output_path):
    """Export model to TFLite with float16 quantization."""
    print(f"\n{'='*60}")
    print("Exporting to TFLite (float16 quantization)")
    print(f"{'='*60}")
    
    # Load the saved model
    model = tf.keras.models.load_model(model_path)
    
    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    
    # Float16 quantization
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    
    tflite_model = converter.convert()
    
    # Save
    with open(output_path, 'wb') as f:
        f.write(tflite_model)
    
    file_size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"  [OK] TFLite model saved: {output_path}")
    print(f"  File size: {file_size_mb:.2f} MB")
    
    if file_size_mb < 10:
        print(f"  [OK] Size target (<10MB) ACHIEVED")
    else:
        print(f"  [Warning] Size target (<10MB) exceeded: {file_size_mb:.2f}MB")
    
    return tflite_model


def verify_tflite(tflite_path, class_names):
    """Verify TFLite model loads and runs inference."""
    print(f"\n{'='*60}")
    print("Verifying TFLite Model")
    print(f"{'='*60}")
    
    # Load model
    interpreter = tf.lite.Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()
    
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    print(f"  Input:  shape={input_details[0]['shape']}, dtype={input_details[0]['dtype']}")
    print(f"  Output: shape={output_details[0]['shape']}, dtype={output_details[0]['dtype']}")
    
    # Create a random test input
    input_shape = input_details[0]['shape']
    test_input = np.random.rand(*input_shape).astype(np.float32)
    
    # Run inference
    interpreter.set_tensor(input_details[0]['index'], test_input)
    interpreter.invoke()
    output = interpreter.get_tensor(output_details[0]['index'])
    
    print(f"\n  Test inference results:")
    print(f"    Output shape: {output.shape}")
    print(f"    Softmax sum:  {output.sum():.4f} (should be ~1.0)")
    print(f"    Predicted class: {class_names[np.argmax(output)]} "
          f"(confidence: {output.max():.4f})")
    
    # Verify output shape
    assert output.shape == (1, len(class_names)), \
        f"Output shape mismatch: {output.shape} vs (1, {len(class_names)})"
    assert abs(output.sum() - 1.0) < 0.01, \
        f"Softmax sum not ~1.0: {output.sum()}"
    
    print(f"\n  [OK] TFLite model verification PASSED")
    
    # Also test with an actual image if available
    test_dir = os.path.join(PROCESSED_DIR, "test")
    sample_image = None
    sample_label = None
    
    for class_dir_name in os.listdir(test_dir):
        class_path = os.path.join(test_dir, class_dir_name)
        if os.path.isdir(class_path):
            imgs = [f for f in os.listdir(class_path) if f.endswith('.jpg')]
            if imgs:
                sample_image = os.path.join(class_path, imgs[0])
                sample_label = class_dir_name
                break
    
    if sample_image:
        from PIL import Image
        img = Image.open(sample_image).resize((224, 224))
        img_array = np.expand_dims(np.array(img, dtype=np.float32), axis=0)
        
        interpreter.set_tensor(input_details[0]['index'], img_array)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details[0]['index'])
        
        pred_class = class_names[np.argmax(output)]
        confidence = output.max()
        
        print(f"\n  Real image test:")
        print(f"    Image:      {os.path.basename(sample_image)}")
        print(f"    True label: {sample_label}")
        print(f"    Predicted:  {pred_class} ({confidence:.1%})")
        print(f"    Match:      {'[OK]' if pred_class == sample_label else '[FAIL]'}")


def main():
    print("=" * 60)
    print("RipenAI - Evaluation & TFLite Export")
    print("=" * 60)
    
    configure_gpu()
    
    # Paths
    model_path = os.path.join(OUTPUTS_DIR, "ripenai_model.keras")
    best_model_path = os.path.join(OUTPUTS_DIR, "best_model.keras")
    history_path = os.path.join(OUTPUTS_DIR, "training_history.pkl")
    report_path = os.path.join(OUTPUTS_DIR, "training_report.png")
    tflite_path = os.path.join(OUTPUTS_DIR, "ripenai.tflite")
    labels_path = os.path.join(OUTPUTS_DIR, "class_labels.json")
    
    # Use best model if available, otherwise final model
    load_path = best_model_path if os.path.exists(best_model_path) else model_path
    if not os.path.exists(load_path):
        print(f"Error: Model not found: {load_path}")
        print("  Run 03_train_model.py first.")
        sys.exit(1)
    
    print(f"\nLoading model from: {load_path}")
    model = tf.keras.models.load_model(load_path)
    
    # Load test dataset
    test_ds, class_names = load_test_dataset()
    
    # Evaluate
    print(f"\n{'='*60}")
    print("Model Evaluation")
    print(f"{'='*60}")
    
    test_loss, test_acc = model.evaluate(test_ds, verbose=1)
    print(f"\n  Test Loss:     {test_loss:.4f}")
    print(f"  Test Accuracy: {test_acc:.4f}")
    
    # Get predictions for detailed report
    y_true, y_pred = get_predictions(model, test_ds)
    
    # Classification report
    print(f"\n{'='*60}")
    print("Classification Report")
    print(f"{'='*60}")
    print(classification_report(y_true, y_pred, target_names=class_names, digits=3))
    
    # Load training history and generate report
    if os.path.exists(history_path):
        with open(history_path, 'rb') as f:
            history = pickle.load(f)
        plot_training_report(history, y_true, y_pred, class_names, report_path)
    else:
        print("[Warning] Training history not found, generating partial report")
        history = {
            'accuracy': [test_acc],
            'val_accuracy': [test_acc],
            'loss': [test_loss],
            'val_loss': [test_loss]
        }
        plot_training_report(history, y_true, y_pred, class_names, report_path)
    
    # Update class_labels.json with correct ordering from test dataset
    class_labels = {str(i): name for i, name in enumerate(class_names)}
    with open(labels_path, 'w') as f:
        json.dump(class_labels, f, indent=2)
    print(f"\n[OK] Class labels updated: {labels_path}")
    
    days_path = os.path.join(OUTPUTS_DIR, "ripeness_days.json")
    if os.path.exists(days_path):
        print(f"[OK] Ripeness day metadata preserved: {days_path}")
    else:
        days = {}
        for class_name in class_names:
            fruit, stage = class_name.rsplit("_", 1)
            days.setdefault(fruit, {})[stage] = 5 if stage == "unripe" else 0 if stage == "ripe" else -1
        with open(days_path, 'w') as f:
            json.dump(days, f, indent=2)
        print(f"[OK] Ripeness days generated: {days_path}")
    
    # Export TFLite
    export_tflite(load_path, tflite_path)
    
    # Verify TFLite
    verify_tflite(tflite_path, class_names)
    
    # Final summary
    tflite_size = os.path.getsize(tflite_path) / (1024 * 1024)
    print(f"\n{'='*60}")
    print("FINAL SUMMARY")
    print(f"{'='*60}")
    print(f"  Model:           MobileNetV2 (transfer learning)")
    print(f"  Classes:         {len(class_names)}")
    print(f"  Test Accuracy:   {test_acc:.1%}")
    print(f"  Target Accuracy: 85%  ->  {'[PASS]' if test_acc >= 0.85 else '[FAIL]'}")
    print(f"  TFLite Size:     {tflite_size:.2f} MB")
    print(f"  Size Target:     <10MB  ->  {'[PASS]' if tflite_size < 10 else '[FAIL]'}")
    print(f"\n  Output Files:")
    
    output_files = [
        ("ripenai.tflite", tflite_path),
        ("class_labels.json", labels_path),
        ("ripeness_days.json", days_path),
        ("training_report.png", report_path),
    ]
    
    for name, path in output_files:
        exists = "[OK]" if os.path.exists(path) else "[MISSING]"
        size = f"({os.path.getsize(path) / 1024:.1f} KB)" if os.path.exists(path) else ""
        print(f"    {exists} {name:25s} {size}")
    
    print(f"\n[OK] Pipeline complete!")


if __name__ == "__main__":
    main()
