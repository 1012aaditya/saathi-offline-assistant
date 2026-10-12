# Saathi चलो — walk mode

Live spoken warnings (Hindi) and vibration for what's ahead — vehicles, people, animals, doors, stairs, furniture, poles — with an approximate distance in metres, from the phone's back camera. A web page that runs entirely in the phone's browser: two small YOLOv8n models on-device via onnxruntime-web, no frames uploaded, works offline after the first load. Separate from the laptop agent: no ADB, no OpenAI, no `saathi/` code.

Example lines: *"सामने दरवाज़ा, लगभग 3 मीटर"* · *"बाईं ओर गाड़ी पास आ रही है, लगभग 6 मीटर"* · *"रुकिए! सामने कुर्सी, 1 मीटर से कम"*.

**Safety stance:** an aid alongside a white cane or guide dog, never a replacement. It only warns; it never says the way is clear or safe to cross (a test enforces this). If the camera stalls or is covered, it says so out loud every 8 s. It cannot see potholes, open drains, kerbs, wires or wet floors.

## How it works

| Step | |
|---|---|
| Detect | `yolov8n-coco-416` every frame (traffic, people, animals, chairs, tables, sofas…) + `yolov8n-oiv7-416` every 3rd frame, only for classes COCO lacks (Door, Stairs, Street light, Tree, Cart, Cabinetry, Waste container…). WebGPU when available, else WASM. |
| Track | Boxes matched across frames by kind; a box must persist 2 frames before it's spoken. |
| Distance | Pinhole estimate: real height (per class, `OBJECTS[*].h`) × focal length ÷ box height. Rough (±30%), spoken as "लगभग", in buckets (1 मीटर से कम / डेढ़ / 2 / ढाई / 3…). A box cut off by the frame edge is spoken as "X मीटर के अंदर". |
| Decide | Per-kind distance thresholds (`DIST`): mention → warn → stop. Getting closer (box growing ≥30%/s) raises the level for vehicles. One line at a time; a more urgent line interrupts a less urgent one. |

## Files

| File | What |
|---|---|
| `core.js` | Pure logic: object list, distance, tracking, levels, which line to speak, camera health. Tested. |
| `detector.js` | onnxruntime-web wrapper: letterbox → YOLOv8 → boxes |
| `app.js` | Camera, both detectors, speech (`speechSynthesis`, hi-IN), vibration, overlay, wake lock |
| `index.html`, `walk.css` | One big start/stop button, high contrast |
| `sw.js` | Offline cache: page network-first, models + libraries cache-first |
| `models/` | Two ONNX models (~27 MB) + class lists. Rebuild with `tools/export_models.py`. |
| `tests/core.test.mjs` | `node --test 'walk/tests/*.test.mjs'` |

## Run

```bash
python3 -m http.server 8790 -d walk --bind 127.0.0.1    # from the repo root
```

- **Laptop (webcam or a street video):** open http://localhost:8790. Use "डेस्क पर टेस्ट" to pick a recorded street video.
- **Android phone over ADB:** `adb reverse tcp:8790 tcp:8790`, then open http://localhost:8790 in Chrome on the phone.
- **Any phone on the same Wi-Fi/hotspot:** the camera needs `https`. Serve `walk/` over HTTPS with a throwaway self-signed certificate on the laptop's LAN address and accept the warning once on the phone.
- **Phone without the laptop (real walk):** host `walk/` on any static `https` host (e.g. GitHub Pages). Open it once with internet; after that it works offline.

The phone needs a Hindi text-to-speech voice (Android: Settings → Text-to-speech → Google → install Hindi). The page says so if none is found. iPhone browsers can't vibrate; Android Chrome can.

## Tuning

Starting guesses in `core.js`: `DIST` (metres per kind), `OBJECTS[*].h` (real heights), `FOCAL_PER_DIAGONAL` (camera), `APPROACH_RATE`, `REPEAT_MS`. Tune on a quiet street with the on-screen boxes (yellow = mention, orange = warn, red = stop, ↑ = getting closer, label shows metres) and the `fps · objects · light · backend` line.

## Licences

YOLOv8 weights: Ultralytics, AGPL-3.0. onnxruntime-web: MIT. Class lists: COCO, Open Images V7.
