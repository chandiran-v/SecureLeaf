// Run with: node loadtest/heap-trend.test.js   (no framework: node:assert only)
'use strict';
const assert = require('node:assert');
const { slope, floor } = require('./heap-trend.js');

// A sawtooth that returns to the same floor has ~0 slope; a floor that creeps up does not.
assert.ok(Math.abs(slope([[0, 100], [1, 100], [2, 100], [3, 100]])) < 1e-9);
assert.ok(Math.abs(slope([[0, 100], [1, 110], [2, 120], [3, 130]]) - 10) < 1e-9);
assert.strictEqual(slope([[0, 5]]), 0);
// a noisy series with a flat floor: rolling minimum ignores the spikes
const noisy = [[0, 100], [1, 400], [2, 100], [3, 350], [4, 100], [5, 420]];
assert.ok(Math.abs(slope(floor(noisy, 2))) < 1e-9);
console.log('heap-trend: ok');
