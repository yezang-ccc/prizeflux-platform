import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.PFX_K6_BASE_URL || '';
const usersFile = __ENV.PFX_K6_USERS_FILE || '';
const campaignsCsv = __ENV.PFX_K6_CAMPAIGN_IDS || '';
const runPrefix = __ENV.PFX_K6_RUN_PREFIX || '';
const summaryPath = __ENV.PFX_K6_SUMMARY_PATH || '';
const rate = Number.parseInt(__ENV.PFX_K6_RATE || '', 10);
const durationSeconds = Number.parseInt(__ENV.PFX_K6_DURATION_SECONDS || '', 10);

if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(baseUrl)) {
  throw new Error('PFX_K6_BASE_URL must target a loopback Campaign HTTP port.');
}
if (!/^[A-Za-z0-9_-]{8,32}-$/.test(runPrefix)) {
  throw new Error('PFX_K6_RUN_PREFIX must be a short unique ASCII prefix ending in a hyphen.');
}
if (!summaryPath.toLowerCase().endsWith('.json')) {
  throw new Error('PFX_K6_SUMMARY_PATH must point to a JSON file.');
}
if (!Number.isInteger(rate) || rate < 1 || rate > 200) {
  throw new Error('PFX_K6_RATE must be an integer from 1 to 200 requests per second.');
}
if (!Number.isInteger(durationSeconds) || durationSeconds < 10 || durationSeconds > 300) {
  throw new Error('PFX_K6_DURATION_SECONDS must be an integer from 10 to 300 seconds.');
}

let userTokens;
try {
  userTokens = JSON.parse(open(usersFile));
} catch {
  throw new Error('PFX_K6_USERS_FILE could not be read as JSON.');
}
if (!Array.isArray(userTokens) || userTokens.length < 1 || userTokens.some((token) => typeof token !== 'string' || token.length < 16)) {
  throw new Error('PFX_K6_USERS_FILE must contain synthetic bearer-token strings.');
}

const campaignIds = campaignsCsv.split(',').map((value) => Number.parseInt(value, 10));
if (campaignIds.length < 1 || campaignIds.some((campaignId) => !Number.isSafeInteger(campaignId) || campaignId < 1)) {
  throw new Error('PFX_K6_CAMPAIGN_IDS must contain positive integer campaign IDs.');
}

const attempts = new Counter('draw_queue_attempts');
const accepted = new Counter('draw_queue_accepted');
const protectedRejections = new Counter('draw_queue_capacity_rejections');
const clientErrors = new Counter('draw_queue_client_errors');
const serverErrors = new Counter('draw_queue_server_errors');
const transportErrors = new Counter('draw_queue_transport_errors');
const unexpectedResponses = new Counter('draw_queue_unexpected_responses');
const acceptedLatency = new Trend('draw_queue_accepted_latency', true);
const responseLatency = new Trend('draw_queue_response_latency', true);
const unexpectedRate = new Rate('draw_queue_unexpected_rate');
const acceptedRate = new Rate('draw_queue_accepted_rate');
const capacityRejectionRate = new Rate('draw_queue_capacity_rejection_rate');
const warmupAttempts = new Counter('draw_queue_warmup_attempts');
const warmupAccepted = new Counter('draw_queue_warmup_accepted');
const warmupUnexpected = new Counter('draw_queue_warmup_unexpected');
const warmupLatency = new Trend('draw_queue_warmup_accepted_latency', true);

// The open arrival model schedules starts independently of response time. The
// generous but bounded VU ceiling makes generator saturation visible as drops.
const preAllocatedVUs = Math.min(400, Math.max(20, rate * 2));
const maxVUs = Math.min(2000, Math.max(preAllocatedVUs, rate * 10));
const plannedIterations = rate * durationSeconds;
const warmupRate = Math.min(5, rate);
const plannedWarmupIterations = warmupRate * 30;

export const options = {
  discardResponseBodies: false,
  scenarios: {
    queue_arrival: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${durationSeconds}s`,
      startTime: '36s',
      exec: 'measured',
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '30s',
    },
    queue_warmup: {
      executor: 'constant-arrival-rate',
      rate: warmupRate,
      timeUnit: '1s',
      startTime: '1s',
      duration: '30s',
      exec: 'warmup',
      preAllocatedVUs: 10,
      maxVUs: 50,
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    dropped_iterations: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '30s' }],
    draw_queue_attempts: [`count>=${plannedIterations}`, `count<=${plannedIterations + 1}`],
    draw_queue_accepted_rate: ['rate>=0.99'],
    draw_queue_capacity_rejection_rate: ['rate<=0.01'],
    draw_queue_unexpected_rate: [
      'rate<=0.001',
      { threshold: 'rate<=0.01', abortOnFail: true, delayAbortEval: '30s' },
    ],
    draw_queue_warmup_attempts: [`count>=${plannedWarmupIterations}`, `count<=${plannedWarmupIterations + 1}`],
    draw_queue_warmup_accepted: [`count>=${plannedWarmupIterations}`, `count<=${plannedWarmupIterations + 1}`],
    draw_queue_warmup_unexpected: ['count==0'],
    draw_queue_accepted_latency: ['p(95)<300', 'p(99)<1000'],
  },
};

function submit(phase) {
  const iteration = (__VU - 1) + __ITER;
  const token = userTokens[iteration % userTokens.length];
  const campaignId = campaignIds[iteration % campaignIds.length];
  const requestKey = `${runPrefix}${phase === 'warmup' ? 'w' : 'm'}-${__VU}-${__ITER}`;
  if (phase === 'warmup') warmupAttempts.add(1);
  else attempts.add(1);

  const response = http.post(
    `${baseUrl}/api/draw-queue`,
    JSON.stringify({ campaignId, requestKey }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      timeout: '10s',
      tags: { name: 'POST /api/draw-queue' },
    },
  );
  if (phase !== 'warmup') responseLatency.add(response.timings.duration);

  let responseBody = null;
  try {
    responseBody = response.json();
  } catch {
    // A missing/unreadable response body is intentionally unexpected.
  }

  if (response.status === 202 && responseBody?.data?.status === 'QUEUED') {
    if (phase === 'warmup') {
      warmupAccepted.add(1);
      warmupLatency.add(response.timings.duration);
    } else {
      accepted.add(1);
      acceptedLatency.add(response.timings.duration);
      acceptedRate.add(true);
      capacityRejectionRate.add(false);
      unexpectedRate.add(false);
    }
    return;
  }

  if (response.status === 503 && responseBody?.code === 'PFX-9001') {
    if (phase === 'warmup') warmupUnexpected.add(1);
    else {
      protectedRejections.add(1);
      acceptedRate.add(false);
      capacityRejectionRate.add(true);
      unexpectedRate.add(false);
    }
    return;
  }

  if (phase === 'warmup') {
    warmupUnexpected.add(1);
    return;
  }
  if (response.status === 0) transportErrors.add(1);
  else if (response.status >= 500) serverErrors.add(1);
  else if (response.status >= 400) clientErrors.add(1);
  unexpectedResponses.add(1);
  acceptedRate.add(false);
  capacityRejectionRate.add(false);
  unexpectedRate.add(true);
}

export function warmup() {
  submit('warmup');
}

export function measured() {
  submit('measured');
}

export function handleSummary(data) {
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
    stdout: `k6 summary saved; warmup=${warmupRate} req/s x 30s; scenario=${rate} req/s x ${durationSeconds}s; planned=${plannedIterations}\n`,
  };
}
