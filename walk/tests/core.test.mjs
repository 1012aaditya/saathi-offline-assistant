// Run: node --test 'walk/tests/*.test.mjs'
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  DIST,
  LABELS,
  OBJECTS,
  PHRASES,
  checkHealth,
  createAnnouncer,
  createHealth,
  createTracker,
  dedupe,
  distanceOf,
  growthRate,
  isClipped,
  levelOf,
  meters,
  phraseFor,
  pickAlert,
  updateTracks,
  zoneOf,
} from "../core.js";

const ASPECT = 0.75; // portrait 3:4, the default in updateTracks
const box = (cx, h, cy = 0.5, w = h / 2) => ({ x: cx - w / 2, y: cy - h / 2, w, h });
const det = (label, cx, h, score = 0.8) => ({ label, score, ...box(cx, h) });
/** Box height on screen for an object of this label at d metres. */
const hAt = (label, d) => (OBJECTS[label].h * 0.6 * Math.hypot(1, ASPECT)) / d;

/** Feed the same object for n frames at 100 ms steps, distance from d0 to d1 metres. */
function feed(label, cx, d0, d1, n = 8) {
  const tracker = createTracker();
  let tracks = [];
  for (let i = 0; i < n; i++) {
    const d = d0 + ((d1 - d0) * i) / (n - 1);
    tracks = updateTracks(tracker, [det(label, cx, hAt(label, d))], i * 100);
  }
  return { tracker, tracks, now: (n - 1) * 100 };
}

test("zones split the frame in thirds", () => {
  assert.equal(zoneOf(box(0.1, 0.1)), "left");
  assert.equal(zoneOf(box(0.5, 0.1)), "center");
  assert.equal(zoneOf(box(0.9, 0.1)), "right");
});

test("distance: a 2 m door half the frame tall is about 3 m away; twice as tall = half as far", () => {
  const d = distanceOf(box(0.5, 0.5), 2.0, ASPECT);
  assert.ok(d > 2.5 && d < 3.5, `door at ${d}`);
  assert.ok(Math.abs(distanceOf(box(0.5, 1.0), 2.0, ASPECT) - d / 2) < 1e-9);
});

test("distance is spoken as rough Hindi buckets", () => {
  assert.equal(meters(0.6), "1 मीटर से कम");
  assert.equal(meters(1.4), "लगभग डेढ़ मीटर");
  assert.equal(meters(2.6), "लगभग ढाई मीटर");
  assert.equal(meters(6.3), "लगभग 6 मीटर");
  assert.equal(meters(2.1, true), "2 मीटर के अंदर"); // clipped box: only an upper bound
});

test("a box cut off by the frame edge is flagged as clipped", () => {
  assert.ok(isClipped({ x: 0.3, y: 0.0, w: 0.3, h: 0.6 }));
  assert.ok(!isClipped(box(0.5, 0.5)));
});

test("no phrase ever claims the way is clear or safe", () => {
  const banned = ["साफ", "सुरक्षित", "safe", "clear", "चलिए", "पार"];
  const lines = [...Object.values(PHRASES)];
  for (const label of LABELS)
    for (const zone of ["left", "center", "right"])
      for (const level of [1, 2, 3])
        for (const approaching of [true, false]) lines.push(phraseFor(label, zone, level, approaching, meters(2)));
  for (const line of lines) for (const word of banned) assert.ok(!line.includes(word), `${line} contains ${word}`);
});

test("phrases are short, name the object and the distance, and use the right gender", () => {
  assert.equal(phraseFor("Door", "center", 1, false, "लगभग 3 मीटर"), "सामने दरवाज़ा, लगभग 3 मीटर");
  assert.equal(phraseFor("car", "left", 2, true, "लगभग 6 मीटर"), "बाईं ओर गाड़ी पास आ रही है, लगभग 6 मीटर");
  assert.equal(phraseFor("truck", "center", 2, true, "लगभग 7 मीटर"), "सामने ट्रक पास आ रहा है, लगभग 7 मीटर");
  assert.equal(phraseFor("chair", "center", 3, false, "1 मीटर से कम"), "रुकिए! सामने कुर्सी, 1 मीटर से कम");
  for (const label of LABELS) assert.ok(phraseFor(label, "right", 2, true, meters(2.6, true)).split(" ").length <= 12);
});

test("every label has a kind with thresholds and a positive real height", () => {
  for (const [label, o] of Object.entries(OBJECTS)) {
    assert.ok(DIST[o.kind], `${label} kind ${o.kind}`);
    assert.ok(o.h > 0, label);
  }
});

test("levels by distance: far is quiet, close ahead warns then stops", () => {
  assert.equal(levelOf("obstacle", "center", 5, 0), 0);
  assert.equal(levelOf("obstacle", "center", 2.8, 0), 1);
  assert.equal(levelOf("obstacle", "center", 1.8, 0), 2);
  assert.equal(levelOf("obstacle", "center", 0.8, 0), 3);
  assert.equal(levelOf("door", "center", 4, 0), 1);
  assert.equal(levelOf("hazard", "center", 1.2, 0), 3); // stairs
  assert.equal(levelOf("person", "center", 4, 0), 0); // people ahead at a distance aren't news
  assert.equal(levelOf("person", "center", 2, 0), 2);
});

test("levels: vehicles approaching from the side warn, parked ones at the side stay quiet", () => {
  assert.equal(levelOf("vehicle", "left", 10, 0), 0);
  assert.equal(levelOf("vehicle", "left", 10, 0.5), 2);
  assert.equal(levelOf("vehicle", "left", 6, 0.5), 3);
  assert.equal(levelOf("vehicle", "left", 14, 1.0), 3); // fast approach
  assert.equal(levelOf("animal", "left", 4, 0.5), 0); // animal off to the side: not a warning
});

test("growth rate needs enough history and measures per-second growth", () => {
  assert.equal(growthRate([{ t: 0, s: 0.1 }]), 0);
  const hist = [0, 100, 200, 300, 400, 500, 600].map((t) => ({ t, s: 0.1 + t / 2000 }));
  const r = growthRate(hist);
  assert.ok(r > 1 && r < 5, `rate ${r}`);
});

test("dedupe keeps one box when both models see the same table", () => {
  const out = dedupe([det("dining table", 0.5, 0.3, 0.7), det("Table", 0.5, 0.31, 0.6), det("person", 0.2, 0.6, 0.6)]);
  assert.deepEqual(out.map((d) => d.label).sort(), ["dining table", "person"]);
});

test("tracker keeps one object across frames and car/truck flicker", () => {
  const tracker = createTracker();
  updateTracks(tracker, [det("car", 0.5, 0.3)], 0);
  updateTracks(tracker, [det("truck", 0.51, 0.31)], 100);
  const tracks = updateTracks(tracker, [det("car", 0.52, 0.32)], 200);
  assert.equal(tracks.length, 1);
  assert.equal(tracks[0].hits, 3);
});

test("tracker drops unknown labels and forgets objects that disappear", () => {
  const tracker = createTracker();
  updateTracks(tracker, [det("car", 0.5, 0.3), det("Toaster", 0.2, 0.3)], 0);
  assert.equal(tracker.tracks.length, 1);
  assert.equal(updateTracks(tracker, [], 3000).length, 0);
});

test("a single-frame detection is not announced (flicker filter)", () => {
  const tracks = updateTracks(createTracker(), [det("car", 0.5, 0.7)], 0);
  assert.equal(pickAlert(tracks, 0, createAnnouncer()), null);
});

test("a door ahead is announced with its distance", () => {
  const { tracks, now } = feed("Door", 0.5, 3, 3);
  assert.equal(pickAlert(tracks, now, createAnnouncer()).text, "सामने दरवाज़ा, लगभग 3 मीटर");
});

test("approaching car from the left is announced once, then not repeated right away", () => {
  const { tracks, now } = feed("car", 0.15, 12, 9);
  assert.ok(tracks[0].approaching);
  const ann = createAnnouncer();
  const alert = pickAlert(tracks, now, ann);
  assert.equal(alert.level, 2);
  assert.match(alert.text, /^बाईं ओर गाड़ी पास आ रही है, लगभग \d+ मीटर$/);
  assert.equal(pickAlert(tracks, now + 500, ann), null);
});

test("walking toward a door: announced again as it gets noticeably closer", () => {
  const tracker = createTracker();
  const ann = createAnnouncer();
  let tracks;
  for (let i = 0; i < 3; i++) tracks = updateTracks(tracker, [det("Door", 0.5, hAt("Door", 4.5))], i * 100);
  assert.match(pickAlert(tracks, 200, ann).text, /लगभग 5 मीटर/);
  for (let i = 3; i < 6; i++) tracks = updateTracks(tracker, [det("Door", 0.5, hAt("Door", 4.3))], 5000 + i * 100);
  assert.equal(pickAlert(tracks, 5500, ann), null); // barely closer: stay quiet
  for (let i = 6; i < 9; i++) tracks = updateTracks(tracker, [det("Door", 0.5, hAt("Door", 2.9))], 6000 + i * 100);
  assert.match(pickAlert(tracks, 6800, ann).text, /लगभग 3 मीटर/);
});

test("escalation interrupts: the same object getting close is announced again at once", () => {
  const tracker = createTracker();
  const ann = createAnnouncer();
  let tracks;
  for (let i = 0; i < 3; i++) tracks = updateTracks(tracker, [det("chair", 0.5, hAt("chair", 1.8))], i * 100);
  assert.equal(pickAlert(tracks, 200, ann).level, 2);
  for (let i = 3; i < 5; i++) tracks = updateTracks(tracker, [det("chair", 0.5, hAt("chair", 0.8))], i * 100);
  const alert = pickAlert(tracks, 400, ann, 2);
  assert.equal(alert.level, 3);
  assert.ok(alert.interrupt);
});

test("a less urgent object never talks over a more urgent line", () => {
  const { tracks, now } = feed("cow", 0.5, 4, 4);
  assert.equal(pickAlert(tracks, now, createAnnouncer(), 2), null);
  assert.match(pickAlert(tracks, now, createAnnouncer(), 0).text, /^सामने गाय, लगभग 4 मीटर$/);
});

test("most urgent object wins when several are visible", () => {
  const tracker = createTracker();
  let tracks;
  for (let i = 0; i < 3; i++)
    tracks = updateTracks(tracker, [det("Door", 0.5, hAt("Door", 4)), det("motorcycle", 0.2, hAt("motorcycle", 3))], i * 100);
  // the bike is parked to the side (not approaching) but within its stop distance: mention level, closer wins
  assert.match(pickAlert(tracks, 200, createAnnouncer()).text, /बाइक/);
  for (let i = 3; i < 6; i++)
    tracks = updateTracks(tracker, [det("Door", 0.5, hAt("Door", 4)), det("Stairs", 0.5, hAt("Stairs", 1.2))], i * 100);
  assert.match(pickAlert(tracks, 500, createAnnouncer()).text, /^रुकिए! सामने सीढ़ियाँ/);
});

test("health: a stalled or covered camera is announced, and repeated", () => {
  const h = createHealth();
  assert.equal(checkHealth(h, { now: 1000, lastFrameAt: 900, brightness: 0.5 }), null);
  assert.equal(checkHealth(h, { now: 5000, lastFrameAt: 1000, brightness: 0.5 }), PHRASES.stall);
  assert.equal(checkHealth(h, { now: 6000, lastFrameAt: 1000, brightness: 0.5 }), null); // not every tick
  assert.equal(checkHealth(h, { now: 14000, lastFrameAt: 13900, brightness: 0.02 }), PHRASES.dark);
});
