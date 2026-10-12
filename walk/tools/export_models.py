"""Rebuild walk/models/ from the official Ultralytics weights (downloaded from their GitHub releases).

    uv run --python 3.12 --with ultralytics --with onnx --with onnxslim python walk/tools/export_models.py

Both models are YOLOv8n exported to ONNX at 416 px (opset 12) for onnxruntime-web.
Licence: Ultralytics YOLOv8 is AGPL-3.0.
"""

import json
import shutil
from pathlib import Path

from ultralytics import YOLO

OUT = Path(__file__).resolve().parent.parent / "models"
MODELS = {"yolov8n.pt": "coco", "yolov8n-oiv7.pt": "oiv7"}  # COCO 80 classes · Open Images V7 601 classes

OUT.mkdir(exist_ok=True)
for weights, tag in MODELS.items():
    model = YOLO(weights)
    (OUT / f"{tag}.json").write_text(json.dumps([model.names[i] for i in range(len(model.names))]))
    onnx = model.export(format="onnx", imgsz=416, opset=12, simplify=True)
    shutil.move(onnx, OUT / f"yolov8n-{tag}-416.onnx")
    print("wrote", OUT / f"yolov8n-{tag}-416.onnx")
