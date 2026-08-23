"""Convert the farmer ONNX model to a fixed-shape float32 TFLite model."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import onnx2tf.onnx2tf as converter


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--feature-dim", type=int, default=57)
    args = parser.parse_args()
    if args.feature_dim <= 0:
        raise ValueError("--feature-dim must be positive")
    args.output.mkdir(parents=True, exist_ok=True)

    # The model is float32 and does not need onnx2tf's downloaded image sample.
    # A deterministic vector also keeps conversion reproducible and offline.
    converter.download_test_image_data = lambda: np.zeros((1, args.feature_dim), dtype=np.float32)
    converter.convert(
        input_onnx_file_path=str(args.input),
        output_folder_path=str(args.output),
        batch_size=1,
        overwrite_input_shape=[f"features:1,{args.feature_dim}"],
        keep_nwc_or_nhwc_or_ndhwc_input_names=["features"],
        not_use_onnxsim=True,
        verbosity="warn",
    )
    tflite_files = sorted(args.output.glob("*.tflite"))
    if not tflite_files:
        raise RuntimeError(f"No TFLite output produced in {args.output}")
    model_path = tflite_files[-1]
    promoted_path = args.output / "farmer_risk.tflite"
    if model_path != promoted_path:
        promoted_path.write_bytes(model_path.read_bytes())

    # Verify the converted artifact before it is copied into Android assets.
    try:
        import tensorflow as tf

        interpreter = tf.lite.Interpreter(model_path=str(promoted_path))
        interpreter.allocate_tensors()
        input_details = interpreter.get_input_details()[0]
        output_details = interpreter.get_output_details()[0]
        input_data = np.zeros(tuple(input_details["shape"]), dtype=np.float32)
        interpreter.set_tensor(input_details["index"], input_data)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details["index"])
        print(json.dumps({
            "tflite": str(promoted_path),
            "input_shape": input_details["shape"].tolist(),
            "output_shape": output_details["shape"].tolist(),
            "zero_input_output": output.reshape(-1).tolist(),
        }, indent=2))
    except ImportError:
        print(promoted_path)


if __name__ == "__main__":
    main()
