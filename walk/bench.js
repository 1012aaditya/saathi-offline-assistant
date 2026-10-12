// Depth-model benchmark for walk mode (13 Oct). Times one frame per model/backend on this device.
// Models in models/bench/ (gitignored): MiDaS v2.1 small (MIT), Depth Anything V2 Small (Apache-2.0).
import * as ort from "./vendor/ort.all.min.mjs";

ort.env.wasm.wasmPaths = new URL("./vendor/", import.meta.url).href;
ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1;

const CONFIGS = [
  ["YOLOv8n (today, reference)", "models/yolov8n-coco-320.onnx", 320, "webgpu"],
  ["MiDaS small", "models/bench/midas-small-256.onnx", 256, "webgpu"],
  ["MiDaS small", "models/bench/midas-small-256.onnx", 256, "wasm"],
  ["Depth Anything V2 S fp16", "models/bench/dav2-small-fp16.onnx", 196, "webgpu"],
  ["Depth Anything V2 S int8", "models/bench/dav2-small-int8.onnx", 196, "wasm"],
  ["Depth Anything V2 S fp16", "models/bench/dav2-small-fp16.onnx", 252, "webgpu"],
  ["Depth Anything V2 S int8", "models/bench/dav2-small-int8.onnx", 252, "wasm"],
];

const $ = (id) => document.getElementById(id);
const log = (m) => ($("log").textContent += m + "\n");
$("env").textContent =
  `${navigator.userAgent.match(/\(([^)]+)\)/)?.[1] ?? ""} · cores ${navigator.hardwareConcurrency} · ` +
  `isolated ${self.crossOriginIsolated} → WASM threads ${ort.env.wasm.numThreads} · WebGPU ${"gpu" in navigator}`;

window.__bench = [];

async function time(url, size, ep) {
  const s = await ort.InferenceSession.create(url, { executionProviders: [ep] });
  const x = new ort.Tensor("float32", new Float32Array(3 * size * size).fill(0.5), [1, 3, size, size]);
  const feeds = { [s.inputNames[0]]: x };
  await s.run(feeds);
  await s.run(feeds);
  const t0 = performance.now();
  for (let i = 0; i < 5; i++) await s.run(feeds);
  const ms = (performance.now() - t0) / 5;
  await s.release?.();
  return ms;
}

$("go").addEventListener("click", async () => {
  $("go").disabled = true;
  $("rows").innerHTML = "";
  window.__bench = [];
  for (const [name, url, size, ep] of CONFIGS) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${name}</td><td>${size}</td><td>${ep}${ep === "wasm" ? "×" + ort.env.wasm.numThreads : ""}</td><td>…</td>`;
    $("rows").append(tr);
    let cell;
    try {
      const ms = await time(url, size, ep);
      cell = `<span class="${ms < 150 ? "ok" : ms < 400 ? "slow" : "bad"}">${Math.round(ms)}</span>`;
      window.__bench.push({ name, size, ep, ms: Math.round(ms) });
    } catch (e) {
      cell = `<span class="bad">failed</span>`;
      log(`${name} ${size} ${ep}: ${String(e.message || e).slice(0, 120)}`);
      window.__bench.push({ name, size, ep, error: String(e.message || e).slice(0, 80) });
    }
    tr.lastElementChild.innerHTML = cell;
  }
  $("done").textContent = "Done. Green < 150 ms (real-time), yellow < 400 ms, red = too slow for walking.";
  $("go").disabled = false;
});
