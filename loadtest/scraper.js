// Phase 11 D8 — k6 "scraper" scenario: ONE buyer hammering the tile endpoint as fast as it can.
//
//   login -> startViewerSession -> loop { viewerPageUrl -> GET tile }  with NO think time
//
// This is the attacker the rate limiter exists for. A honest reader (loadtest/viewer.js) turns a
// page every few seconds and never sees a 429; this script asks for pages as fast as the network
// allows. Expected result with the default limits (ratelimit.tile: burst 5, 2 tokens/s;
// ratelimit.page-url: burst 10, 4 tokens/s):
//   * the great majority of requests are 429 (tile_limited / pageurl_limited)
//   * tile_ok grows at about 2 per second (plus the 5-token burst at the very start)
//
// Env (all optional):
//   BASE_URL   backend base URL                 (default http://host.docker.internal:8080)
//   USERS_CSV  credentials from LoadTestSeeder  (default /loadtest/users.csv) - first row is used
//   DURATION   how long to hammer               (default 60s)
//   TILE_OK_PER_SECOND_MAX  threshold on the effective successful-tile rate (default 3)
//
// Run it against a backend started with SPRING_PROFILES_ACTIVE=dev,loadtest (see README.md).

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERS_CSV = __ENV.USERS_CSV || '/loadtest/users.csv';
const DURATION = __ENV.DURATION || '60s';
const TILE_OK_PER_SECOND_MAX = parseFloat(__ENV.TILE_OK_PER_SECOND_MAX || '3');

const tileOk = new Counter('tile_ok');
const tileLimited = new Counter('tile_limited');
const pageUrlLimited = new Counter('pageurl_limited');
const otherFailures = new Counter('other_failures');

export const options = {
  scenarios: {
    scraper: { executor: 'constant-vus', vus: 1, duration: DURATION },
  },
  // 429 is the EXPECTED answer here, so the response callback below tells k6 not to count it as a failure.
  thresholds: {
    tile_ok: [`rate<${TILE_OK_PER_SECOND_MAX}`],   // effective throughput is capped near 2/s
    tile_limited: ['count>0'],                      // and the limiter demonstrably said no
    other_failures: ['count<5'],
  },
};

let users = [];
try {
  users = open(USERS_CSV).split('\n').map((l) => l.trim()).filter((l) => l.length > 0).slice(1).map((line) => {
    const [email, password, productId] = line.split(',');
    return { email, password, productId };
  });
} catch (e) {
  users = [];
}

http.setResponseCallback(http.expectedStatuses(200, 429));

function gql(query, variables, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = http.post(`${BASE_URL}/graphql`, JSON.stringify({ query, variables }), { headers, tags: { name: 'graphql' } });
  let body = null;
  try {
    body = res.json();
  } catch (e) {
    body = null;
  }
  return body || {};
}

export function setup() {
  if (users.length === 0) {
    fail(`${USERS_CSV} is empty or missing. Start the backend with the loadtest profile first (loadtest/README.md).`);
  }
  const user = users[0];
  const login = gql(
    'mutation($e: String!, $p: String!) { login(input: { email: $e, password: $p }) { accessToken } }',
    { e: user.email, p: user.password });
  const token = login.data && login.data.login && login.data.login.accessToken;
  if (!token) fail('login failed');
  const start = gql(
    'mutation($p: ID!, $f: String!) { startViewerSession(productId: $p, deviceFingerprint: $f) { sessionToken pageCount } }',
    { p: user.productId, f: 'k6-scraper' }, token);
  const session = start.data && start.data.startViewerSession;
  if (!session) fail('could not start a viewer session (is the buyer entitled?)');
  return { token, sessionToken: session.sessionToken, pages: session.pageCount || 1 };
}

export default function (ctx) {
  const page = 1 + Math.floor(Math.random() * ctx.pages);
  const body = gql('query($t: String!, $p: Int!) { viewerPageUrl(sessionToken: $t, pageNumber: $p) { url } }',
    { t: ctx.sessionToken, p: page }, ctx.token);

  const limited = (body.errors || []).some((e) => e.extensions && e.extensions.code === 'RATE_LIMITED');
  if (limited) {
    pageUrlLimited.add(1);   // the scraper does not wait: it just tries again immediately
    return;
  }
  const url = body.data && body.data.viewerPageUrl && body.data.viewerPageUrl.url;
  if (!url) {
    otherFailures.add(1);
    return;
  }

  const res = http.get(`${BASE_URL}${url}`, {
    headers: { Authorization: `Bearer ${ctx.token}` },
    tags: { name: 'tile' },
    responseType: 'binary',
  });
  if (res.status === 200) tileOk.add(1);
  else if (res.status === 429) {
    tileLimited.add(1);
    check(res, { '429 carries Retry-After': (r) => !!r.headers['Retry-After'] });
  } else otherFailures.add(1);
}
