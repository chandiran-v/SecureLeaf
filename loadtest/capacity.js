// Phase 17 D1 — k6 capacity matrix: 500 -> 1,000 -> 2,500 -> 5,000 readers, a hold at every step.
//
// Same reader as viewer.js (login -> startViewerSession -> heartbeat + read pages), but:
//   * a reader MIX, fixed per virtual user: 70% sequential, 20% back-navigation, 10% mobile variant
//   * one ramp + one HOLD per step; every request is tagged step=hold-<N> (or step=ramp), so each
//     step has its own thresholds and its own numbers in the summary
//   * rate limits are NOT touched: a reader at THINK_TIME 5 s must never see a 429. If one does it
//     is counted (tile_limited) and excluded from the error rate - it is the system working - but a
//     non-zero count at honest load means the limits are too tight (record it in the report).
//
// Pass criteria PER HOLD (docs/phases/phase-17-capacity-verification.md, D1):
//   tile p95 < 500 ms, p99 < 1 s, error rate < 0.5 % (429s excluded).
// The fourth criterion - no heap growth trend during a hold - cannot be seen from k6; run
// loadtest/heap-trend.js against Prometheus afterwards (see loadtest/README.md).
//
// Env (all optional):
//   BASE_URL         backend base URL                        (default http://host.docker.internal:8080)
//   USERS_CSV        credentials from LoadTestSeeder         (default /loadtest/users.csv)
//   STEPS            VU target per step, comma separated     (default 500,1000,2500,5000)
//   HOLD             hold time per step                      (default 10m)
//   RAMP             ramp time into each step                (default 2m)
//   THINK_TIME       seconds spent "reading" a page          (default 5)
//   HEARTBEAT_EVERY  seconds between heartbeats              (default 15)
//   BACK_NAV_RATE    back-navigation chance for the 20% persona (default 0.4, ~ the Phase 13 assumption)
//   TILE_P95_MS / TILE_P99_MS / MAX_ERROR_RATE   thresholds  (defaults 500 / 1000 / 0.005)
//   GENERATORS / GENERATOR_INDEX   D2: this k6 process is generator I of N (default 1 / 0). Each one
//                    runs ceil(target / N) VUs and its own slice of users.csv - see
//                    loadtest/docker-compose.k6.yml.
//
// CI-scale example (Phase 17 D4; NOT capacity evidence):
//   STEPS=20,50,100 HOLD=1m RAMP=15s THINK_TIME=1 k6 run capacity.js

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERS_CSV = __ENV.USERS_CSV || '/loadtest/users.csv';
const STEPS = (__ENV.STEPS || '500,1000,2500,5000').split(',').map((v) => parseInt(v.trim(), 10));
const HOLD = __ENV.HOLD || '10m';
const RAMP = __ENV.RAMP || '2m';
const THINK_TIME = parseFloat(__ENV.THINK_TIME || '5');
const HEARTBEAT_EVERY = parseFloat(__ENV.HEARTBEAT_EVERY || '15');
const BACK_NAV_RATE = parseFloat(__ENV.BACK_NAV_RATE || '0.4');
const TILE_P95_MS = parseInt(__ENV.TILE_P95_MS || '500', 10);
const TILE_P99_MS = parseInt(__ENV.TILE_P99_MS || '1000', 10);
const MAX_ERROR_RATE = parseFloat(__ENV.MAX_ERROR_RATE || '0.005');
const GENERATORS = parseInt(__ENV.GENERATORS || '1', 10);
const GENERATOR_INDEX = parseInt(__ENV.GENERATOR_INDEX || '0', 10);
const TOKEN_MAX_AGE_S = 10 * 60;

// D1 reader mix, by position in a block of ten users: 0-6 sequential, 7-8 back-navigation, 9 mobile.
const PERSONAS = ['sequential', 'sequential', 'sequential', 'sequential', 'sequential', 'sequential',
  'sequential', 'backnav', 'backnav', 'mobile'];

const tileErrors = new Rate('tile_errors'); // any non-429 failure on the reader's requests
const tileLimited = new Counter('tile_limited'); // intended 429s - excluded from the error rate
const sessionsLost = new Counter('viewer_sessions_lost');
const tileBytes = new Trend('tile_bytes', false);

// "Expected" for k6's own http_req_failed: 2xx/3xx and 429. A 503 (load shed) still counts as failed.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

const perGenerator = (n) => Math.ceil(n / GENERATORS);

// Stage list and the same schedule in seconds (for tagging requests with the step they belong to).
function toSeconds(d) {
  const m = /^(\d+)(s|m|h)$/.exec(d);
  if (!m) return fail(`bad duration "${d}" (use e.g. 90s, 10m)`);
  return parseInt(m[1], 10) * { s: 1, m: 60, h: 3600 }[m[2]];
}
const SCHEDULE = []; // [{ name, endsAt }]
{
  let t = 0;
  for (const target of STEPS) {
    t += toSeconds(RAMP);
    SCHEDULE.push({ name: 'ramp', endsAt: t });
    t += toSeconds(HOLD);
    SCHEDULE.push({ name: `hold-${target}`, endsAt: t });
  }
}

function stepAt(elapsedS) {
  for (const s of SCHEDULE) if (elapsedS < s.endsAt) return s.name;
  return 'ramp'; // ramp-down
}

const thresholds = {};
for (const target of STEPS) {
  thresholds[`http_req_duration{name:tile,step:hold-${target}}`] = [`p(95)<${TILE_P95_MS}`, `p(99)<${TILE_P99_MS}`];
  thresholds[`tile_errors{step:hold-${target}}`] = [`rate<${MAX_ERROR_RATE}`];
}

export const options = {
  scenarios: {
    readers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: STEPS.flatMap((target) => [
        { duration: RAMP, target: perGenerator(target) },
        { duration: HOLD, target: perGenerator(target) },
      ]),
      gracefulRampDown: '30s',
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// `k6 inspect` must work without seeded data, so a missing file is tolerated here; setup() fails a
// real run with a clear message.
let users = [];
try {
  users = parseCsv(open(USERS_CSV));
} catch (e) {
  users = [];
}

function parseCsv(text) {
  const lines = text.split('\n').map((l) => l.trim()).filter((l) => l.length > 0);
  return lines.slice(1).map((line) => {
    const [email, password, productId] = line.split(',');
    return { email, password, productId };
  });
}

let currentStep = 'ramp';

function gql(query, variables, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = http.post(`${BASE_URL}/graphql`, JSON.stringify({ query, variables }), {
    headers,
    tags: { name: 'graphql', step: currentStep },
  });
  let body = null;
  try {
    body = res.json();
  } catch (e) {
    body = null;
  }
  const limited = !!body && (body.errors || []).some((e) => e.extensions && e.extensions.code === 'RATE_LIMITED');
  return { res, limited, data: body && body.data ? body.data : null };
}

const LOGIN = `mutation($email: String!, $password: String!) {
  login(input: { email: $email, password: $password }) { accessToken }
}`;
const START = `mutation($productId: ID!, $fp: String!) {
  startViewerSession(productId: $productId, deviceFingerprint: $fp) {
    sessionToken pageCount heartbeatIntervalSeconds
  }
}`;
const PAGE_URL = `query($token: String!, $page: Int!, $variant: String) {
  viewerPageUrl(sessionToken: $token, pageNumber: $page, variant: $variant) { url }
}`;
const HEARTBEAT = `mutation($token: String!) { viewerHeartbeat(sessionToken: $token) { status } }`;
const END = `mutation($token: String!) { endViewerSession(sessionToken: $token) }`;

let auth = null;
let session = null;

function ensureLogin(user) {
  const now = Date.now() / 1000;
  if (auth && now - auth.at < TOKEN_MAX_AGE_S) return true;
  const { data } = gql(LOGIN, { email: user.email, password: user.password });
  const token = data && data.login && data.login.accessToken;
  const ok = check(token, { 'logged in': (t) => !!t });
  tileErrors.add(!ok, { step: currentStep });
  if (!ok) return false;
  auth = { token, at: now };
  return true;
}

function startSession(user) {
  const { data } = gql(START, { productId: user.productId, fp: `k6-g${GENERATOR_INDEX}-vu-${__VU}` }, auth.token);
  const s = data && data.startViewerSession;
  const ok = check(s, { 'session started': (x) => !!x });
  tileErrors.add(!ok, { step: currentStep });
  if (!ok) return false;
  session = { token: s.sessionToken, pages: s.pageCount || 1, lastHeartbeat: Date.now() / 1000 };
  return true;
}

function heartbeatIfDue() {
  const now = Date.now() / 1000;
  if (now - session.lastHeartbeat < HEARTBEAT_EVERY) return true;
  session.lastHeartbeat = now;
  const { data } = gql(HEARTBEAT, { token: session.token }, auth.token);
  const status = data && data.viewerHeartbeat && data.viewerHeartbeat.status;
  if (status !== 'ACTIVE') {
    sessionsLost.add(1);
    session = null;
    return false;
  }
  return true;
}

// true = carry on (page delivered, or intended 429); false = real failure, abandon this session.
function readPage(page, variant) {
  const { data, limited } = gql(PAGE_URL, { token: session.token, page, variant }, auth.token);
  if (limited) {
    tileLimited.add(1);
    return true;
  }
  const url = data && data.viewerPageUrl && data.viewerPageUrl.url;
  if (!url) {
    tileErrors.add(true, { step: currentStep });
    return false;
  }
  const res = http.get(`${BASE_URL}${url}`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    tags: { name: 'tile', step: currentStep },
    responseType: 'binary',
  });
  if (res.status === 429) {
    tileLimited.add(1);
    return true;
  }
  const ok = res.status === 200;
  tileErrors.add(!ok, { step: currentStep });
  if (ok) tileBytes.add(res.body.byteLength);
  return ok;
}

function thinkWithHeartbeats(seconds) {
  let left = seconds;
  while (left > 0 && session) {
    const slice = Math.min(left, HEARTBEAT_EVERY);
    sleep(slice);
    left -= slice;
    heartbeatIfDue();
  }
}

export function setup() {
  const maxVus = Math.max(...STEPS);
  if (users.length < maxVus) {
    fail(`STEPS peak at ${maxVus} but ${USERS_CSV} has only ${users.length} users. Re-run the seeder with LOADTEST_USERS >= ${maxVus}.`);
  }
  return { startMs: Date.now() };
}

export default function (data) {
  // Each generator owns a contiguous slice of users.csv; the persona follows the GLOBAL user index
  // so the 70/20/10 mix holds across all generators together.
  const slice = perGenerator(Math.max(...STEPS));
  const userIndex = GENERATOR_INDEX * slice + (__VU - 1);
  const user = users[userIndex % users.length];
  const persona = PERSONAS[userIndex % 10];
  const variant = persona === 'mobile' ? 'MOBILE' : 'DESKTOP';
  const backNavRate = persona === 'backnav' ? BACK_NAV_RATE : 0;
  currentStep = stepAt((Date.now() - data.startMs) / 1000);

  if (!ensureLogin(user)) {
    sleep(5);
    return;
  }
  if (!session && !startSession(user)) {
    sleep(5);
    return;
  }

  let page = 1;
  while (session && page <= session.pages) {
    currentStep = stepAt((Date.now() - data.startMs) / 1000);
    if (!readPage(page, variant)) break;
    thinkWithHeartbeats(THINK_TIME);
    if (page > 1 && Math.random() < backNavRate && session) {
      readPage(page - 1, variant);
      thinkWithHeartbeats(THINK_TIME);
    }
    page += 1;
  }

  if (session) {
    gql(END, { token: session.token }, auth.token);
    session = null;
  }
}
