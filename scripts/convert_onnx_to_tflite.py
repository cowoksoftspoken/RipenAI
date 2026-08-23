"""Convert a native CUDA ONNX candidate to a fixed NHWC float32 TFLite model."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnx2tf.onnx2tf as converter


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    # onnx2tf 1.28.8 downloads a pickled calibration image with NumPy 2.x.
    # Conversion does not need that network sample for a float32 model, so use
    # a deterministic zero sample and keep conversion reproducible/offline.
    converter.download_test_image_data = lambda: np.zeros((1, 224, 224, 3), dtype=np.float32)
    converter.convert(
        input_onnx_file_path=str(args.input),
        output_folder_path=str(args.output),
        batch_size=1,
        overwrite_input_shape=["image:1,224,224,3"],
        keep_nwc_or_nhwc_or_ndhwc_input_names=["image"],
        not_use_onnxsim=True,
        verbosity="warn",
    )
    tflite_files = sorted(args.output.glob("*.tflite"))
    if not tflite_files:
        raise RuntimeError(f"No TFLite output produced in {args.output}")
    print(tflite_files[-1])


if __name__ == "__main__":
    main()
