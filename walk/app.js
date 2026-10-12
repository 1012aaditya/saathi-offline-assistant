/* Walk mode page: camera → on-device object detector → core.js → Hindi speech + vibration.
   Everything runs in the phone's browser; no frame ever leaves the device. */

import {
  LABELS,
  OBJECTS,
  PHRASES,
  checkHealth,
  createAnnouncer,
  createHealth,
  createTracker,
  pickAlert,
  updateTracks,
} from "./core.js";
import { createDetector } from "./detector.js";

// Main model every frame (traffic, people, furniture); extra model every EXTRA_EVERY frames
// for things the main one doesn't know (doors, stairs, poles…). Both YOLOv8n, exported at 416.
const MODELS = {
  main: { url: "models/yolov8n-coco-416.onnx", names: "models/coco.json" },
  extra: { url: "models/yolov8n-oiv7-416.onnx", names: "models/oiv7.json" },
};
const EXTRA_EVERY = 3;
const VIBRATE = { 1: [80], 2: [200, 100, 200], 3: [400, 100, 400, 100, 400] };
const BOX_COLOR = { 0: "#888", 1: "#ffd400", 2: "#ff8c00", 3: "#ff2020" };
const SPEAK_ALWAYS = 9; // start/stop/errors outrank every warning

const $ = (id) => document.getElementById(id);
const toggle = $("toggle");
const statusEl = $("status");
const stage = $("stage");
const video = $("video");
const overlay = $("overlay");
const stats = $("stats");
const ctx = overlay.getContext("2d");
const probe = Object.assign(document.createElement("canvas"), { width: 16, height: 16 });
const probeCtx = probe.getContext("2d", { willReadFrequently: true });

let detector = null; // { main, extra (null if it failed to load), backend }
let running = false;
let stream = null;
let wakeLock = null;
let watchdog = null;
let tracker, announcer, health;
let lastFrameAt = 0;
let lastVideoTime = -1;
let brightness = null;
let frames = 0;
let fpsWindow = { at: 0, frames: 0, fps: 0 };

stage.hidden = true;

// ---------- speech ----------
let hindiVoice = null;
let current = null; // utterance being spoken
let currentLevel = 0;

function pickVoice() {
  const voices = speechSynthesis.getVoices();
  hindiVoice = voices.find((v) => v.lang?.toLowerCase().replace("_", "-").startsWith("hi")) ?? null;
}
pickVoice();
speechSynthesis.addEventListener?.("voiceschanged", pickVoice);

function busyLevel() {
  return speechSynthesis.speaking && current ? currentLevel : 0;
}

function speak(text, level) {
  if (level < SPEAK_ALWAYS && busyLevel() >= level) return;
  if (speechSynthesis.speaking) speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.lang = "hi-IN";
  if (hindiVoice) u.voice = hindiVoice;
  u.rate = 1.1;
  const done = () => {
    if (current === u) [current, currentLevel] = [null, 0];
  };
  u.onend = done;
  u.onerror = done;
  [current, currentLevel] = [u, level];
  speechSynthesis.speak(u);
}

function vibrate(level) {
  navigator.vibrate?.(VIBRATE[level] ?? 0);
}

function setStatus(text) {
  statusEl.textContent = text;
}

// ---------- detector ----------
async function loadModel({ url, names }) {
  const list = await (await fetch(names)).json();
  return createDetector(url, list, new Set(LABELS));
}

async function loadDetector() {
  const [main, extra] = await Promise.allSettled([loadModel(MODELS.main), loadModel(MODELS.extra)]);
  if (main.status === "rejected") throw main.reason;
  if (extra.status === "rejected") console.warn("door/stairs model unavailable", extra.reason);
  return { main: main.value, extra: extra.status === "fulfilled" ? extra.value : null, backend: main.value.backend };
}

async function detectAll(source, frame) {
  const dets = await detector.main.detect(source);
  if (detector.extra && frame % EXTRA_EVERY === 0) dets.push(...(await detector.extra.detect(source)));
  return dets;
}

const ready = loadDetector().then(
  (d) => {
    detector = d;
    toggle.disabled = false;
    toggle.textContent = "चलना शुरू करें";
    const notes = [hindiVoice ? "" : "हिंदी आवाज़ नहीं मिली — फ़ोन की सेटिंग में Hindi TTS जोड़ें", d.extra ? "" : "दरवाज़े/सीढ़ी पहचान बंद"];
    setStatus(["तैयार।", ...notes.filter(Boolean)].join(" "));
  },
  (e) => {
    console.error(e);
    toggle.textContent = "लोड नहीं हुआ";
    setStatus(`${PHRASES.loadFailed} (${e.message ?? e})`);
  },
);

// ---------- start / stop ----------
async function start(source) {
  speak(PHRASES.start, SPEAK_ALWAYS); // inside the tap handler, so the browser allows audio
  await ready;
  if (!detector) return;
  try {
    if (source) {
      video.srcObject = null;
      video.src = URL.createObjectURL(source);
    } else {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" }, width: { ideal: 640 }, height: { ideal: 480 } },
        audio: false,
      });
      video.srcObject = stream;
    }
    await video.play();
  } catch (e) {
    console.error(e);
    speak(PHRASES.noCamera, SPEAK_ALWAYS);
    setStatus(`${PHRASES.noCamera} (${e.name ?? e})`);
    stopMedia();
    return;
  }
  try {
    wakeLock = await navigator.wakeLock?.request("screen");
  } catch {
    // Screen may sleep; the stall warning will tell the user.
  }
  tracker = createTracker();
  announcer = createAnnouncer();
  health = createHealth();
  lastFrameAt = performance.now();
  lastVideoTime = -1;
  brightness = null;
  running = true;
  stage.hidden = false;
  toggle.dataset.running = "true";
  toggle.textContent = "रोकें";
  setStatus("चल रहा है।");
  watchdog = setInterval(checkCamera, 500);
  requestAnimationFrame(loop);
}

function stopMedia() {
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  video.pause();
  if (video.src) URL.revokeObjectURL(video.src);
  video.removeAttribute("src");
  video.srcObject = null;
}

function stop(reason = PHRASES.stop) {
  running = false;
  clearInterval(watchdog);
  stopMedia();
  wakeLock?.release?.().catch(() => {});
  wakeLock = null;
  toggle.dataset.running = "false";
  toggle.textContent = "चलना शुरू करें";
  ctx.clearRect(0, 0, overlay.width, overlay.height);
  stage.hidden = true;
  setStatus(reason);
  speak(reason, SPEAK_ALWAYS);
}

toggle.addEventListener("click", () => (running ? stop() : start()));
$("file").addEventListener("change", (e) => {
  const file = e.target.files?.[0];
  if (!file) return;
  if (running) stop();
  start(file);
});
video.addEventListener("ended", () => running && stop("वीडियो ख़त्म।"));

document.addEventListener("visibilitychange", async () => {
  if (!running || document.visibilityState !== "visible") return;
  lastFrameAt = performance.now(); // don't count the time in the background as a stall
  try {
    wakeLock = await navigator.wakeLock?.request("screen"); // released by the browser when hidden
  } catch {}
});

// ---------- per-frame work ----------
let inflight = false;

function loop() {
  if (!running) return;
  if (!inflight && video.readyState >= 2 && video.currentTime !== lastVideoTime) {
    inflight = true;
    lastVideoTime = video.currentTime;
    detectAll(video, frames)
      .then(onDetections, (e) => console.error("detect failed", e))
      .finally(() => (inflight = false));
  }
  requestAnimationFrame(loop);
}

function onDetections(dets) {
  if (!running) return;
  {
    const now = performance.now();
    lastFrameAt = now;
    frames++;
    const tracks = updateTracks(tracker, dets, now, video.videoWidth / video.videoHeight);
    const alert = pickAlert(tracks, now, announcer, busyLevel());
    if (alert) {
      speak(alert.text, alert.level);
      vibrate(alert.level);
      setStatus(alert.text);
    }
    if (frames % 15 === 0) brightness = measureBrightness();
    draw(tracks);
    updateStats(now, tracks.length);
  }
}

function checkCamera() {
  if (!running) return;
  const warning = checkHealth(health, { now: performance.now(), lastFrameAt, brightness });
  if (warning) {
    speak(warning, 3);
    vibrate(3);
    setStatus(warning);
  }
}

function measureBrightness() {
  probeCtx.drawImage(video, 0, 0, 16, 16);
  const px = probeCtx.getImageData(0, 0, 16, 16).data;
  let sum = 0;
  for (let i = 0; i < px.length; i += 4) sum += 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
  return sum / (px.length / 4) / 255;
}

function draw(tracks) {
  const dpr = window.devicePixelRatio || 1;
  const w = Math.round(overlay.clientWidth * dpr);
  const h = Math.round(overlay.clientHeight * dpr);
  if (overlay.width !== w || overlay.height !== h) [overlay.width, overlay.height] = [w, h];
  ctx.clearRect(0, 0, w, h);
  ctx.lineWidth = 4 * dpr;
  ctx.font = `bold ${16 * dpr}px system-ui, sans-serif`;
  for (const t of tracks) {
    const color = BOX_COLOR[t.level];
    const [x, y, bw, bh] = [t.box.x * w, t.box.y * h, t.box.w * w, t.box.h * h];
    ctx.strokeStyle = color;
    ctx.strokeRect(x, y, bw, bh);
    const label = `${OBJECTS[t.label].word} ${t.dist.toFixed(1)}m${t.approaching ? " ↑" : ""}`;
    ctx.fillStyle = color;
    ctx.fillRect(x, Math.max(0, y - 22 * dpr), ctx.measureText(label).width + 10 * dpr, 22 * dpr);
    ctx.fillStyle = "#000";
    ctx.fillText(label, x + 5 * dpr, Math.max(16 * dpr, y - 5 * dpr));
  }
}

function updateStats(now, count) {
  fpsWindow.frames++;
  if (now - fpsWindow.at >= 1000) {
    fpsWindow = { at: now, frames: 0, fps: fpsWindow.frames };
    const light = brightness === null ? "–" : brightness.toFixed(2);
    stats.textContent = `${fpsWindow.fps} fps · ${count} objects · light ${light} · ${detector.backend}`;
  }
}

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch((e) => console.warn("offline cache unavailable", e));
}
