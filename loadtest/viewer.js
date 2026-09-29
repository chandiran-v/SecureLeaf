// Phase 10 D5 — k6 "reader" scenario: every virtual user is a buyer reading one book.
//
//   login -> startViewerSession -> [ heartbeat every 15 s ] + [ read pages one by one ]
//
// Each page read = viewerPageUrl (GraphQL) + GET of the signed tile URL, tagged name:tile so the
// threshold and the summary isolate the hot path. Think time models a human reading a page.
//
// Env (all optional):
//   BASE_URL         backend base URL                       (default http://host.docker.internal:8080)
//   USERS_CSV        credentials from LoadTestSeeder        (default /loadtest/users.csv)
//   VUS              stage targets, comma separated         (default 50,200,500)
//   STAGE_DURATION   time per stage                         (default 1m)
//   THINK_TIME       seconds spent "reading" a page         (default 5)
//   HEARTBEAT_EVERY  seconds between heartbeats             (default 15)
//   BACK_NAV_RATE    chance of going back one page, 0..1    (default 0.1)
//   TILE_P95_MS      threshold for p95 of tile requests     (default 500)
//
// One user per VU: the same buyer in two VUs would supersede its own session (one active session
// per buyer+product), so VUS must not exceed the number of rows in users.csv.

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERS_CSV = __ENV.USERS_CSV || '/loadtest/users.csv';
const STAGE_TARGETS = (__ENV.VUS || '50,200,500').split(',').map((v) => parseInt(v.trim(), 10));
const STAGE_DURATION = __ENV.STAGE_DURATION || '1m';
const THINK_TIME = parseFloat(__ENV.THINK_TIME || '5');
const HEARTBEAT_EVERY = parseFloat(__ENV.HEARTBEAT_EVERY || '15');
const BACK_NAV_RATE = parseFloat(__ENV.BACK_NAV_RATE || '0.1');
const TILE_P95_MS = parseInt(__ENV.TILE_P95_MS || '500', 10);
const TOKEN_MAX_AGE_S = 10 * 60; // access tokens live 15 min; refresh by logging in again

const sessionsLost = new Counter('viewer_sessions_lost');

export const options = {
  scenarios: {
    readers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: STAGE_TARGETS.map((target) => ({ duration: STAGE_DURATION, target })),
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    'http_req_duration{name:tile}': [`p(95)<${TILE_P95_MS}`],
    http_req_failed: ['rate<0.01'],
  },
};

// Read once in the init context and shared by every VU (a SharedArray would save memory at
// thousands of users; a plain array is fine for the few hundred we use).
// A missing file is tolerated here so `k6 inspect` (which only reads the options) works without
// seeded data; setup() below fails a real run with a clear message instead.
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

function gql(query, variables, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = http.post(`${BASE_URL}/graphql`, JSON.stringify({ query, variables }), {
    headers,
    tags: { name: 'graphql' },
  });
  let body = null;
  try {
    body = res.json();
  } catch (e) {
    body = null;
  }
  return { res, data: body && body.data ? body.data : null };
}

const LOGIN = `mutation($email: String!, $password: String!) {
  login(input: { email: $email, password: $password }) { accessToken }
}`;
const START = `mutation($productId: ID!, $fp: String!) {
  startViewerSession(productId: $productId, deviceFingerprint: $fp) {
    sessionToken pageCount heartbeatIntervalSeconds
  }
}`;
const PAGE_URL = `query($token: String!, $page: Int!) {
  viewerPageUrl(sessionToken: $token, pageNumber: $page) { url }
}`;
const HEARTBEAT = `mutation($token: String!) { viewerHeartbeat(sessionToken: $token) { status } }`;
const END = `mutation($token: String!) { endViewerSession(sessionToken: $token) }`;

// Per-VU state: module-level variables are private to each VU in k6.
let auth = null; // { token, at }
let session = null; // { token, pages, lastHeartbeat }

function ensureLogin(user) {
  const now = Date.now() / 1000;
  if (auth && now - auth.at < TOKEN_MAX_AGE_S) return true;
  const { data } = gql(LOGIN, { email: user.email, password: user.password });
  const token = data && data.login && data.login.accessToken;
  if (!check(token, { 'logged in': (t) => !!t })) return false;
  auth = { token, at: now };
  return true;
}

function startSession(user) {
  const { data } = gql(START, { productId: user.productId, fp: `k6-vu-${__VU}` }, auth.token);
  const s = data && data.startViewerSession;
  if (!check(s, { 'session started': (x) => !!x })) return false;
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

function readPage(page) {
  const { data } = gql(PAGE_URL, { token: session.token, page }, auth.token);
  const url = data && data.viewerPageUrl && data.viewerPageUrl.url;
  if (!check(url, { 'got signed url': (u) => !!u })) return false;
  // Fetched immediately: the URL is single-use and only valid for 30 s.
  const res = http.get(`${BASE_URL}${url}`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    tags: { name: 'tile' },
    responseType: 'binary',
  });
  return check(res, { 'tile 200': (r) => r.status === 200 });
}

// Sleeps for `seconds` but sends heartbeats meanwhile, like the real reader page does.
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
  const maxVus = Math.max(...STAGE_TARGETS);
  if (users.length < maxVus) {
    fail(`VUS peaks at ${maxVus} but ${USERS_CSV} has only ${users.length} users. Re-run the seeder with LOADTEST_USERS >= ${maxVus}.`);
  }
}

export default function () {
  const user = users[(__VU - 1) % users.length];
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
    if (!readPage(page)) break;
    thinkWithHeartbeats(THINK_TIME);
    // Occasional back-navigation: a reader flips back one page, then carries on.
    if (page > 1 && Math.random() < BACK_NAV_RATE && session) {
      readPage(page - 1);
      thinkWithHeartbeats(THINK_TIME);
    }
    page += 1;
  }

  // Finished the book: close the session politely, then start over as the next iteration.
  if (session) {
    gql(END, { token: session.token }, auth.token);
    session = null;
  }
}
