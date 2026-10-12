/* YOLOv8 ONNX models in the browser via onnxruntime-web.
   Video frame → letterboxed square tensor → boxes for the classes walk mode cares about. */

import * as ort from "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/ort.all.min.mjs";

ort.env.wasm.wasmPaths = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/";

/**
 * names: class index → class name. wanted: names to keep.
 * Returns { detect(source) → Promise<[{label, score, x, y, w, h}]> (0..1 frame fractions), backend }.
 */
export async function createDetector(modelUrl, names, wanted, { size = 416, threshold = 0.3 } = {}) {
  const SIZE = size; // must match the size the model was exported at
  const keep = names.map((n, i) => (wanted.has(n) ? i : -1)).filter((i) => i >= 0);
  let session = null;
  let backend = "";
  for (const ep of ["webgpu", "wasm"]) {
    try {
      session = await ort.InferenceSession.create(modelUrl, { executionProviders: [ep] });
      backend = ep;
      break;
    } catch (e) {
      console.warn(`onnxruntime ${ep} unavailable`, e);
    }
  }
  if (!session) throw new Error("model failed to load");

  const canvas = new OffscreenCanvas(SIZE, SIZE);
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const input = new Float32Array(3 * SIZE * SIZE);
  const plane = SIZE * SIZE;

  async function detect(source) {
    const W = source.videoWidth ?? source.width;
    const H = source.videoHeight ?? source.height;
    const scale = Math.min(SIZE / W, SIZE / H);
    const dw = W * scale;
    const dh = H * scale;
    const dx = (SIZE - dw) / 2;
    const dy = (SIZE - dh) / 2;
    ctx.fillStyle = "rgb(114,114,114)";
    ctx.fillRect(0, 0, SIZE, SIZE);
    ctx.drawImage(source, dx, dy, dw, dh);
    const px = ctx.getImageData(0, 0, SIZE, SIZE).data;
    for (let i = 0, p = 0; i < plane; i++, p += 4) {
      input[i] = px[p] / 255;
      input[plane + i] = px[p + 1] / 255;
      input[2 * plane + i] = px[p + 2] / 255;
    }
    const feeds = { [session.inputNames[0]]: new ort.Tensor("float32", input, [1, 3, SIZE, SIZE]) };
    const out = (await session.run(feeds))[session.outputNames[0]];
    const [, , n] = out.dims; // [1, 4 + classes, anchors]
    const data = out.data;
    const dets = [];
    for (let a = 0; a < n; a++) {
      let best = -1;
      let score = threshold;
      for (const c of keep) {
        const s = data[(4 + c) * n + a];
        if (s > score) [best, score] = [c, s];
      }
      if (best < 0) continue;
      const cx = data[a];
      const cy = data[n + a];
      const bw = data[2 * n + a];
      const bh = data[3 * n + a];
      const x = (cx - bw / 2 - dx) / dw;
      const y = (cy - bh / 2 - dy) / dh;
      const x2 = Math.min(1, x + bw / dw);
      const y2 = Math.min(1, y + bh / dh);
      const x1 = Math.max(0, x);
      const y1 = Math.max(0, y);
      dets.push({ label: names[best], score, x: x1, y: y1, w: x2 - x1, h: y2 - y1 });
    }
    return nms(dets, 0.5);
  }

  return { detect, backend };
}

function iou(a, b) {
  const ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
  const iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
  const inter = ix * iy;
  return inter / (a.w * a.h + b.w * b.h - inter || 1);
}

/** Per-class non-max suppression (core.dedupe then merges look-alike classes). */
function nms(dets, overlap) {
  const kept = [];
  for (const d of dets.sort((a, b) => b.score - a.score)) {
    if (!kept.some((k) => k.label === d.label && iou(k, d) > overlap)) kept.push(d);
    if (kept.length >= 20) break;
  }
  return kept;
}
