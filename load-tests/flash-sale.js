// Flash-sale load test: many buyers race for a limited stock of one SKU.
//
//   docker compose --profile app up -d --build
//   docker run --rm -i --network host grafana/k6 run - < load-tests/flash-sale.js
//
// Env: BASE_URL (default http://localhost:8081), KEYCLOAK_URL (http://localhost:8180),
//      STOCK (1000), BUYERS (20000), VUS (300),
//      GATE ("true" arms the Redis admission gate, "false" sends everything to MongoDB),
//      P99_MS (1000) latency budget for reservation requests.
//
// The run fails unless exactly STOCK units are sold: overselling or underselling under load
// is a correctness bug, not a performance issue.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const TOKEN_URL = `${__ENV.KEYCLOAK_URL || 'http://localhost:8180'}/realms/souqly/protocol/openid-connect/token`;
const STOCK = parseInt(__ENV.STOCK || '1000', 10);
const BUYERS = parseInt(__ENV.BUYERS || '20000', 10);
const VUS = parseInt(__ENV.VUS || '300', 10);
const GATE = (__ENV.GATE || 'true') === 'true';
const P99_MS = parseInt(__ENV.P99_MS || '1000', 10);

// Same callers as production: operations staff set up the sale, the order service reserves.
function clientToken(clientId, secret) {
  const res = http.post(TOKEN_URL, { grant_type: 'client_credentials', client_id: clientId, client_secret: secret });
  if (res.status !== 200) fail(`token for ${clientId} failed: ${res.status} ${res.body}`);
  return res.json('access_token');
}

function headers(token) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
}

const created = new Counter('reservations_created');
const soldOut = new Counter('reservations_sold_out');
const unexpected = new Counter('reservations_unexpected');

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  scenarios: {
    flash_sale: { executor: 'shared-iterations', vus: VUS, iterations: BUYERS, maxDuration: '5m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    reservations_unexpected: ['count==0'],
    'http_req_duration{name:reserve}': [`p(99)<${P99_MS}`],
  },
};

export function setup() {
  const sku = `FLASH-${Date.now()}`;
  const ops = clientToken('souqly-ops', 'souqly-ops-dev-secret');
  const restock = http.post(`${BASE_URL}/api/v1/stock/${sku}/restock`, JSON.stringify({ quantity: STOCK }), headers(ops));
  if (restock.status !== 200) fail(`restock failed: ${restock.status} ${restock.body}`);
  if (GATE) {
    const arm = http.put(`${BASE_URL}/api/v1/flash-sales/${sku}`, '{}', headers(ops));
    if (arm.status !== 200) fail(`arming gate failed: ${arm.status} ${arm.body}`);
  }
  // Access tokens live 5 minutes, longer than the test.
  return { sku, ops, orderService: clientToken('order-service', 'order-service-dev-secret') };
}

export default function ({ sku, orderService }) {
  const body = JSON.stringify({ orderId: `o-${__VU}-${__ITER}-${Date.now()}`, lines: [{ sku, quantity: 1 }] });
  const res = http.post(`${BASE_URL}/api/v1/reservations`, body, { ...headers(orderService), tags: { name: 'reserve' } });
  if (res.status === 201) created.add(1);
  else if (res.status === 409 && res.json('code') === 'INSUFFICIENT_STOCK') soldOut.add(1);
  else unexpected.add(1);
  check(res, { 'reserved or sold out': (r) => r.status === 201 || r.status === 409 });
}

export function teardown({ sku, ops }) {
  const stock = http.get(`${BASE_URL}/api/v1/stock/${sku}`, headers(ops)).json();
  console.log(`final stock for ${sku}: available=${stock.available} reserved=${stock.reserved}`);
  if (stock.available !== 0 || stock.reserved !== STOCK) {
    fail(`expected exactly ${STOCK} units reserved, got available=${stock.available} reserved=${stock.reserved}`);
  }
}
