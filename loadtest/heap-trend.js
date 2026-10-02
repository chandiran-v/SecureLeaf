#!/usr/bin/env node
// Phase 17 D1 — "no heap growth trend during a hold", the one pass criterion k6 cannot see.
//
//   node loadtest/heap-trend.js --prometheus http://localhost:9090 --start 2026-10-01T10:00:00Z \
//        --end 2026-10-01T10:10:00Z [--max-mb-per-min 5]
//
// Queries the heap that is still live AFTER each garbage collection (jvm_gc_live_data_size_bytes),
// fits a least-squares line through it and prints the slope. Raw heap used is a sawtooth (allocate,
// collect, allocate...) whose slope is noise; the post-GC floor only rises if something is being
// retained, i.e. a leak. The floor is a rolling minimum (--floor-window samples, default 8 x 15 s),
// because in-flight tiles inflate single readings. Needs a hold of several minutes: with a 1-minute
// CI-scale hold the result is noise, not evidence. Exit code 1 when the slope exceeds the limit. Needs Node 18+ (fetch).

'use strict';

/** Least-squares slope of y over x (units of y per unit of x). Pure, so it is unit-tested below. */
function slope(points) {
  const n = points.length;
  if (n < 2) return 0;
  const mx = points.reduce((a, p) => a + p[0], 0) / n;
  const my = points.reduce((a, p) => a + p[1], 0) / n;
  let num = 0;
  let den = 0;
  for (const [x, y] of points) {
    num += (x - mx) * (y - my);
    den += (x - mx) ** 2;
  }
  return den === 0 ? 0 : num / den;
}

/**
 * Rolling minimum over `w` samples. The live-data gauge also holds whatever tiles are in flight at
 * the moment of the collection (a 350 KB tile per queued request), which swings by hundreds of MB
 * with load; the lowest value in a window is the part that is retained no matter what is in flight.
 */
function floor(points, w) {
  const out = [];
  for (let i = w - 1; i < points.length; i++) {
    out.push([points[i][0], Math.min(...points.slice(i - w + 1, i + 1).map((p) => p[1]))]);
  }
  return out;
}

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > -1 ? process.argv[i + 1] : fallback;
}

async function main() {
  const base = arg('prometheus', 'http://localhost:9090');
  const start = Date.parse(arg('start'));
  const end = Date.parse(arg('end'));
  const limit = parseFloat(arg('max-mb-per-min', '5'));
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    console.error('usage: heap-trend.js --start <ISO> --end <ISO> [--prometheus URL] [--max-mb-per-min N]');
    process.exit(2);
  }
  const url = `${base}/api/v1/query_range?query=${encodeURIComponent('sum(jvm_gc_live_data_size_bytes)')}`
    + `&start=${start / 1000}&end=${end / 1000}&step=15`;
  const body = await (await fetch(url)).json();
  const series = body.data && body.data.result && body.data.result[0];
  if (!series || series.values.length < 4) {
    console.error('no jvm_gc_live_data_size_bytes samples in that window (needs at least one full GC cycle).');
    process.exit(2);
  }
  const points = series.values.map(([t, v]) => [(t - series.values[0][0]) / 60, parseFloat(v) / 1048576]);
  const window = parseInt(arg('floor-window', '8'), 10); // 8 samples x 15 s = 2 min
  const trend = floor(points, Math.min(window, points.length - 1));
  const mbPerMin = slope(trend);
  const verdict = mbPerMin <= limit ? 'PASS' : 'FAIL';
  console.log(`live heap floor after GC: ${trend[0][1].toFixed(1)} MB -> ${trend[trend.length - 1][1].toFixed(1)} MB `
    + `over ${points[points.length - 1][0].toFixed(1)} min; slope ${mbPerMin.toFixed(2)} MB/min (limit ${limit}) ${verdict}`);
  process.exit(verdict === 'PASS' ? 0 : 1);
}

if (require.main === module) {
  main().catch((e) => {
    console.error(e);
    process.exit(2);
  });
}
module.exports = { slope, floor };
