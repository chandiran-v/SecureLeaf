// Phase 17 D6 — regression guard: 20 readers for 60 s, tile p95 < 500 ms.
//
// Small enough for a nightly CI job (docs/ci/perf-smoke.yml.example). It proves "the viewer's hot
// path did not get slower since last night" - it is NOT a capacity result (see capacity.js).
//
// It is viewer.js with a fixed shape, so a change to the reader logic lives in one place:
// `k6 run -e ... smoke.js` is `viewer.js` with VUS=20,20 STAGE_DURATION=30s THINK_TIME=1 and the
// p95 threshold pinned. Kept as its own file (not a flag) so the CI command is obvious and nobody
// loosens the threshold through an env var by accident.
//
// Env: BASE_URL (default http://host.docker.internal:8080), USERS_CSV (default /loadtest/users.csv).
// Needs >= 20 seeded users (LOADTEST_USERS=20 or more).

import http from 'k6/http';
import { check, sleep, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERS_CSV = __ENV.USERS_CSV || '/loadtest/users.csv';
const VUS = 20;
const HOLD_SECONDS = 60;
const THINK_TIME = 1;

export const options = {
  scenarios: {
    smoke: { executor: 'constant-vus', vus: VUS, duration: `${HOLD_SECONDS}s`, gracefulStop: '15s' },
  },
  thresholds: {
    'http_req_duration{name:tile}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

let users = [];
try {
  users = open(USERS_CSV).split('\n').map((l) => l.trim()).filter((l) => l.length > 0).slice(1)
    .map((line) => {
      const [email, password, productId] = line.split(',');
      return { email, password, productId };
    });
} catch (e) {
  users = [];
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
  return body && body.data ? body.data : null;
}

export function setup() {
  if (users.length < VUS) {
    fail(`smoke needs ${VUS} users but ${USERS_CSV} has ${users.length}. Seed with LOADTEST_USERS >= ${VUS}.`);
  }
}

export default function () {
  const user = users[(__VU - 1) % users.length];
  const login = gql(
    'mutation($email: String!, $password: String!) { login(input: { email: $email, password: $password }) { accessToken } }',
    { email: user.email, password: user.password });
  const token = login && login.login && login.login.accessToken;
  if (!check(token, { 'logged in': (t) => !!t })) {
    sleep(5);
    return;
  }
  const started = gql(
    'mutation($productId: ID!, $fp: String!) { startViewerSession(productId: $productId, deviceFingerprint: $fp) { sessionToken pageCount } }',
    { productId: user.productId, fp: `k6-smoke-${__VU}` }, token);
  const s = started && started.startViewerSession;
  if (!check(s, { 'session started': (x) => !!x })) {
    sleep(5);
    return;
  }
  for (let page = 1; page <= (s.pageCount || 1); page++) {
    const p = gql(
      'query($token: String!, $page: Int!) { viewerPageUrl(sessionToken: $token, pageNumber: $page) { url } }',
      { token: s.sessionToken, page }, token);
    const url = p && p.viewerPageUrl && p.viewerPageUrl.url;
    if (!check(url, { 'got signed url': (u) => !!u })) break;
    const res = http.get(`${BASE_URL}${url}`, { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'tile' } });
    check(res, { 'tile 200': (r) => r.status === 200 });
    sleep(THINK_TIME);
  }
  gql('mutation($token: String!) { endViewerSession(sessionToken: $token) }', { token: s.sessionToken }, token);
}
