/* Walk mode decision logic: detections in, spoken warnings out.
   Pure functions + small state objects, no DOM or camera, so it runs under `node --test`.

   Safety stance: this only ever WARNS. It never says a path or road is clear,
   because a missed detection would then turn into an instruction to walk into danger. */

// Class names from two models: lowercase = COCO (yolov8n, every frame, most accurate for
// traffic, people and furniture); Capitalised = Open Images (yolov8n-oiv7, every 3rd frame,
// only for things COCO doesn't know: doors, stairs, poles…). Names are unique across both.
// word: Hindi · fem: gender for "आ रही / आ रहा" · kind: sets distance thresholds ·
// h: typical real height in metres, used to estimate distance from box height.
export const OBJECTS = {
  car: { word: "गाड़ी", fem: true, kind: "vehicle", h: 1.5 },
  truck: { word: "ट्रक", fem: false, kind: "vehicle", h: 3.0 },
  bus: { word: "बस", fem: true, kind: "vehicle", h: 3.0 },
  motorcycle: { word: "बाइक", fem: true, kind: "vehicle", h: 1.1 },
  bicycle: { word: "साइकिल", fem: true, kind: "vehicle", h: 1.0 },
  person: { word: "कोई", fem: false, kind: "person", h: 1.65 },
  dog: { word: "कुत्ता", fem: false, kind: "animal", h: 0.6 },
  cow: { word: "गाय", fem: true, kind: "animal", h: 1.4 },
  horse: { word: "घोड़ा", fem: false, kind: "animal", h: 1.6 },
  cat: { word: "बिल्ली", fem: true, kind: "animal", h: 0.3 },
  chair: { word: "कुर्सी", fem: true, kind: "obstacle", h: 0.9 },
  couch: { word: "सोफ़ा", fem: false, kind: "obstacle", h: 0.85 },
  bed: { word: "बिस्तर", fem: false, kind: "obstacle", h: 0.6 },
  "dining table": { word: "टेबल", fem: true, kind: "obstacle", h: 0.75 },
  bench: { word: "बेंच", fem: true, kind: "obstacle", h: 0.8 },
  "potted plant": { word: "गमला", fem: false, kind: "obstacle", h: 0.6 },
  "fire hydrant": { word: "पाइप", fem: false, kind: "obstacle", h: 0.8 },
  toilet: { word: "टॉयलेट", fem: false, kind: "obstacle", h: 0.8 },
  refrigerator: { word: "फ़्रिज", fem: false, kind: "obstacle", h: 1.7 },
  suitcase: { word: "सूटकेस", fem: false, kind: "obstacle", h: 0.65 },
  Door: { word: "दरवाज़ा", fem: false, kind: "door", h: 2.0 },
  Stairs: { word: "सीढ़ियाँ", fem: true, kind: "hazard", h: 1.0 },
  Ladder: { word: "सीढ़ी", fem: true, kind: "obstacle", h: 2.0 },
  Table: { word: "टेबल", fem: true, kind: "obstacle", h: 0.75 },
  "Coffee table": { word: "टेबल", fem: true, kind: "obstacle", h: 0.45 },
  Desk: { word: "डेस्क", fem: true, kind: "obstacle", h: 0.75 },
  Stool: { word: "स्टूल", fem: false, kind: "obstacle", h: 0.6 },
  Cabinetry: { word: "अलमारी", fem: true, kind: "obstacle", h: 1.8 },
  Bookcase: { word: "अलमारी", fem: true, kind: "obstacle", h: 1.8 },
  "Waste container": { word: "कूड़ेदान", fem: false, kind: "obstacle", h: 0.9 },
  Box: { word: "डिब्बा", fem: false, kind: "obstacle", h: 0.4 },
  Cart: { word: "ठेला", fem: false, kind: "obstacle", h: 1.0 },
  Wheelchair: { word: "व्हीलचेयर", fem: true, kind: "obstacle", h: 0.9 },
  "Street light": { word: "खंभा", fem: false, kind: "obstacle", h: 6.0 },
  Tree: { word: "पेड़", fem: false, kind: "obstacle", h: 5.0 },
};
export const LABELS = Object.keys(OBJECTS);

// Distance thresholds in metres. mention: say once · warn · stop. Starting guesses — tune on a real walk.
export const DIST = {
  vehicle: { mention: 15, warn: 8, stop: 4 },
  person: { mention: 0, warn: 2.5, stop: 1.2 }, // people ahead at a distance aren't news
  animal: { mention: 5, warn: 3, stop: 1.5 },
  hazard: { mention: 6, warn: 3, stop: 1.5 },
  door: { mention: 5, warn: 2, stop: 0.8 },
  obstacle: { mention: 3, warn: 2, stop: 1 },
};

// Focal length in units of the frame diagonal, for a typical phone main camera (~26 mm equivalent).
export const FOCAL_PER_DIAGONAL = 0.6;
export const APPROACH_RATE = 0.3; // box-height growth per second that counts as "getting closer"
export const FAST_APPROACH_RATE = 0.8; // a vehicle growing this fast is a stop warning
export const MIN_HITS = 2; // frames an object must be seen before we speak (filters flicker)
export const TRACK_TTL_MS = 1200; // forget an object not seen for this long
export const HISTORY_MS = 1500; // size history used for the approach rate
export const REPEAT_MS = { 1: 20000, 2: 5000, 3: 2500 }; // re-announce the same object at the same level
export const UPDATE_MS = 4000; // re-announce sooner when its distance bucket shrank
export const GAP_MS = 1500; // min gap between two announcements of equal or lower level

export const PHRASES = {
  start: "चलना मोड शुरू। यह छड़ी की जगह नहीं, सिर्फ़ चेतावनी देगा।",
  stop: "चलना मोड बंद।",
  stall: "सावधान! कैमरा रुक गया है।",
  dark: "सावधान! कैमरा ढका है या अंधेरा है।",
  loadFailed: "चलना मोड चालू नहीं हो पाया।",
  noCamera: "कैमरा नहीं मिला। अनुमति दीजिए।",
};

export function zoneOf(box) {
  const cx = box.x + box.w / 2;
  if (cx < 1 / 3) return "left";
  if (cx > 2 / 3) return "right";
  return "center";
}

const WHERE = { left: "बाईं ओर", right: "दाईं ओर", center: "सामने" };

/** Box touches the top or bottom edge, so the object is taller than it looks: it is at most this far. */
export function isClipped(box) {
  return box.y < 0.02 || box.y + box.h > 0.98;
}

/**
 * Pinhole estimate: distance = real height × focal length / height on screen.
 * aspect = frame width / height. Rough (±30%) — always spoken as "लगभग".
 */
export function distanceOf(box, realH, aspect) {
  const focalPerHeight = FOCAL_PER_DIAGONAL * Math.hypot(1, aspect);
  return (realH * focalPerHeight) / Math.max(box.h, 0.01);
}

/** Bucketed, speakable distance: "1 मीटर से कम", "लगभग डेढ़ मीटर", "3 मीटर के अंदर". */
export function meters(d, clipped = false) {
  if (d < 1) return "1 मीटर से कम";
  const n = d < 3 ? Math.round(d * 2) / 2 : Math.round(d);
  const spoken = { 1.5: "डेढ़", 2.5: "ढाई" }[n] ?? String(n);
  return clipped ? `${spoken} मीटर के अंदर` : `लगभग ${spoken} मीटर`;
}

export function iou(a, b) {
  const x1 = Math.max(a.x, b.x);
  const y1 = Math.max(a.y, b.y);
  const x2 = Math.min(a.x + a.w, b.x + b.w);
  const y2 = Math.min(a.y + a.h, b.y + b.h);
  const inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  const union = a.w * a.h + b.w * b.h - inter;
  return union > 0 ? inter / union : 0;
}

/** Drop duplicate boxes of the same kind (COCO "dining table" + Open Images "Table"). Highest score wins. */
export function dedupe(dets, overlap = 0.5) {
  const kept = [];
  for (const d of [...dets].sort((a, b) => b.score - a.score)) {
    const kind = OBJECTS[d.label]?.kind;
    if (!kind) continue;
    if (kept.some((k) => OBJECTS[k.label].kind === kind && iou(k, d) > overlap)) continue;
    kept.push(d);
  }
  return kept;
}

function centerDist(a, b) {
  return Math.hypot(a.x + a.w / 2 - (b.x + b.w / 2), a.y + a.h / 2 - (b.y + b.h / 2));
}

/** Size growth per second over the history window (0 when there isn't enough history). */
export function growthRate(hist) {
  if (hist.length < 4) return 0;
  const [a0, a1] = hist;
  const [b0, b1] = hist.slice(-2);
  const dt = ((b0.t + b1.t) / 2 - (a0.t + a1.t) / 2) / 1000;
  if (dt < 0.4) return 0;
  const s0 = (a0.s + a1.s) / 2;
  const s1 = (b0.s + b1.s) / 2;
  return s0 > 0 ? (s1 / s0 - 1) / dt : 0;
}

/** 0 = say nothing, 1 = mention, 2 = warn, 3 = stop. */
export function levelOf(kind, zone, dist, rate) {
  const t = DIST[kind];
  const ahead = zone === "center";
  const approaching = rate >= APPROACH_RATE;
  if (dist <= t.stop && (ahead || approaching)) return 3;
  if (kind === "vehicle" && approaching && dist <= t.warn) return 3;
  if (kind === "vehicle" && rate >= FAST_APPROACH_RATE && dist <= t.mention) return 3;
  if (dist <= t.warn && ahead) return 2;
  if (approaching && dist <= t.mention && (kind === "vehicle" || ahead)) return 2;
  if (dist <= t.stop) return 1; // very close but off to the side and not moving closer
  if (ahead && dist <= t.mention) return 1;
  return 0;
}

/** Short Hindi line for one object. Never a statement that something is clear or safe. */
export function phraseFor(label, zone, level, approaching, distText) {
  const o = OBJECTS[label];
  const where = WHERE[zone];
  if (level >= 3) return `रुकिए! ${where} ${o.word}, ${distText}`;
  if (level === 2 && approaching) return `${where} ${o.word} पास आ ${o.fem ? "रही" : "रहा"} है, ${distText}`;
  return `${where} ${o.word}, ${distText}`;
}

export function createTracker() {
  return { tracks: [], nextId: 1 };
}

/**
 * Match this frame's detections to tracked objects and update their size history.
 * dets: [{label, score, x, y, w, h}] with box values as 0..1 fractions of the frame.
 * aspect: frame width / height. Returns the live tracks with zone/dist/rate/level filled in.
 */
export function updateTracks(tracker, dets, now, aspect = 0.75) {
  const known = dedupe(dets);
  const free = new Set(tracker.tracks);
  for (const d of known) {
    const kind = OBJECTS[d.label].kind;
    let best = null;
    let bestScore = 0;
    for (const t of free) {
      if (t.kind !== kind) continue; // car ↔ truck flicker stays one object
      const overlap = iou(t.box, d);
      const score = overlap >= 0.2 ? overlap : centerDist(t.box, d) < 0.12 ? 0.1 : 0;
      if (score > bestScore) [best, bestScore] = [t, score];
    }
    const box = { x: d.x, y: d.y, w: d.w, h: d.h };
    if (best) {
      free.delete(best);
      Object.assign(best, { label: d.label, box, lastSeen: now, hits: best.hits + 1 });
    } else {
      best = { id: tracker.nextId++, label: d.label, kind, box, lastSeen: now, hits: 1, hist: [] };
      best.announcedLevel = 0;
      best.announcedAt = -Infinity;
      best.announcedDist = Infinity;
      tracker.tracks.push(best);
    }
    best.hist.push({ t: now, s: box.h });
    best.hist = best.hist.filter((h) => now - h.t <= HISTORY_MS);
  }
  tracker.tracks = tracker.tracks.filter((t) => now - t.lastSeen <= TRACK_TTL_MS);
  for (const t of tracker.tracks) {
    t.zone = zoneOf(t.box);
    t.clipped = isClipped(t.box);
    t.dist = distanceOf(t.box, OBJECTS[t.label].h, aspect);
    t.distText = meters(t.dist, t.clipped);
    t.rate = growthRate(t.hist);
    t.approaching = t.rate >= APPROACH_RATE;
    t.level = t.lastSeen === now ? levelOf(t.kind, t.zone, t.dist, t.rate) : 0;
  }
  return tracker.tracks;
}

export function createAnnouncer() {
  return { lastAt: -Infinity, lastLevel: 0 };
}

/**
 * Pick at most one thing to say this frame: the most urgent object that is new, got more
 * urgent, got noticeably closer, or is due a repeat. busyLevel = level of the line still being
 * spoken (0 if silent); only something more urgent may interrupt it.
 */
export function pickAlert(tracks, now, ann, busyLevel = 0) {
  let best = null;
  for (const t of tracks) {
    if (t.level < t.announcedLevel && now - t.announcedAt > 3000) t.announcedLevel = t.level; // allow re-escalation
    if (t.level < 1 || t.hits < MIN_HITS || t.level <= busyLevel) continue;
    const closer = t.dist < t.announcedDist * 0.7 && now - t.announcedAt > UPDATE_MS;
    const due = t.level > t.announcedLevel || closer || now - t.announcedAt > REPEAT_MS[t.level];
    if (!due) continue;
    if (!best || t.level > best.level || (t.level === best.level && t.dist < best.dist)) best = t;
  }
  if (!best) return null;
  if (now - ann.lastAt < GAP_MS && best.level <= ann.lastLevel) return null;
  best.announcedLevel = best.level;
  best.announcedAt = now;
  best.announcedDist = best.dist;
  ann.lastAt = now;
  ann.lastLevel = best.level;
  return {
    id: best.id,
    level: best.level,
    text: phraseFor(best.label, best.zone, best.level, best.approaching, best.distText),
    interrupt: best.level >= 3,
  };
}

export const STALL_MS = 3000;
export const DARK_LUMA = 0.08;
export const HEALTH_REPEAT_MS = 8000;

export function createHealth() {
  return { lastWarnAt: -Infinity };
}

/** Fail loud: if the camera stops or is covered, the user must know they're unprotected. */
export function checkHealth(h, { now, lastFrameAt, brightness }) {
  let problem = null;
  if (now - lastFrameAt > STALL_MS) problem = "stall";
  else if (brightness !== null && brightness < DARK_LUMA) problem = "dark";
  if (!problem || now - h.lastWarnAt < HEALTH_REPEAT_MS) return null;
  h.lastWarnAt = now;
  return PHRASES[problem];
}
