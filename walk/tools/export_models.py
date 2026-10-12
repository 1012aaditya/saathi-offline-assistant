"""Rebuild walk/models/ from the official Ultralytics weights (downloaded from their GitHub releases).

    uv run --python 3.12 --with ultralytics --with onnx --with onnxslim python walk/tools/export_models.py [SIZE]

Both models are YOLOv8n exported to ONNX (opset 12) for onnxruntime-web, default 320 px:
~1.7x less compute than 416 for lower latency while walking (13 Oct).
Licence: Ultralytics YOLOv8 is AGPL-3.0.
"""

import json
import shutil
import sys
from pathlib import Path

from ultralytics import YOLO

OUT = Path(__file__).resolve().parent.parent / "models"
MODELS = {
    "yolov8n.pt": "coco",
    "yolov8n-oiv7.pt": "oiv7",
}  # COCO 80 classes · Open Images V7 601 classes

SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 320
OUT.mkdir(exist_ok=True)
for weights, tag in MODELS.items():
    model = YOLO(weights)
    (OUT / f"{tag}.json").write_text(json.dumps([model.names[i] for i in range(len(model.names))]))
    onnx = model.export(format="onnx", imgsz=SIZE, opset=12, simplify=True)
    shutil.move(onnx, OUT / f"yolov8n-{tag}-{SIZE}.onnx")
    print("wrote", OUT / f"yolov8n-{tag}-{SIZE}.onnx")
