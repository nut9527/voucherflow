// 使用唯一用户与活动组合测量领取受理延迟，并单独跟踪最终履约耗时。
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import execution from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const base = (__ENV.VF_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const runId = __ENV.VF_RUN_ID;
if (!runId || !/^[a-zA-Z0-9_-]{1,48}$/.test(runId)) throw new Error('VF_RUN_ID is required');
// 每组输入只领取一次，避免重复领取的幂等响应混入新订单采样。
const accounts = new SharedArray('unique claim subjects', () => {
    const rows = JSON.parse(open(__ENV.VF_ACCOUNTS_FILE || '../.local/perf/accounts.json'));
    const unique = new Set();
    for (const row of rows) {
        if (!row.username || !row.password || !/^[1-9]\d*$/.test(String(row.campaignId))) {
            throw new Error('Every fixture requires username, password and campaignId');
        }
        const key = `${row.username}:${row.campaignId}`;
        if (unique.has(key)) throw new Error('Duplicate user/campaign fixture');
        unique.add(key);
    }
    if (!rows.length) throw new Error('Claim fixtures are empty');
    return rows;
});
const outcomes = new Counter('claim_outcomes');
const claimLatency = new Trend('claim_admission_duration', true);
const completionLatency = new Trend('fulfillment_completion_duration', true);
const replays = new Counter('unexpected_replays');
const terminal = new Counter('fulfillment_outcomes');
const unexpected = new Counter('claim_unexpected_errors');

export const options = {
    summaryTrendStats: ['avg', 'min', 'max', 'p(50)', 'p(95)', 'p(99)'],
    scenarios: {
        claim: {
            executor: 'shared-iterations', vus: Math.min(Number(__ENV.VF_VUS || 10), accounts.length),
            iterations: accounts.length, maxDuration: __ENV.VF_MAX_DURATION || '3m', gracefulStop: '10s',
        },
    },
    setupTimeout: '2m',
    thresholds: { unexpected_replays: ['count==0'], claim_unexpected_errors: ['count==0'] },
};

function csrf() {
    const response = http.get(`${base}/api/auth/csrf`, { tags: { name: 'GET /api/auth/csrf' }, timeout: '5s' });
    if (response.status !== 200) throw new Error('Unable to obtain CSRF token');
    return response.json('data');
}

// 登录与 CSRF 准备在正式迭代前完成，不计入自定义领取和履约延迟。
export function setup() {
    const sessions = [];
    const jar = http.cookieJar();
    for (const account of accounts) {
        jar.clear(base);
        const token = csrf();
        const login = http.post(`${base}/api/auth/login`, {
            username: account.username, password: account.password,
        }, { headers: { [token.headerName]: token.token }, tags: { name: 'POST /api/auth/login' }, timeout: '5s' });
        if (login.status !== 200) throw new Error(`Fixture authentication failed with HTTP ${login.status}`);
        const renewed = csrf();
        const cookies = jar.cookiesForURL(base);
        sessions.push({ campaignId: String(account.campaignId), headers: {
            Cookie: Object.entries(cookies).map(([key, values]) => `${key}=${values[0]}`).join('; '),
            [renewed.headerName]: renewed.token,
        } });
        if (Number(__ENV.VF_LOGIN_SPACING || 0) > 0) sleep(Number(__ENV.VF_LOGIN_SPACING));
    }
    return sessions;
}

export default function (sessions) {
    http.cookieJar().clear(base);
    replays.add(0); unexpected.add(0);
    const session = sessions[execution.scenario.iterationInTest];
    const identity = { runId, fixtureIndex: execution.scenario.iterationInTest, campaignId: session.campaignId };
    const begin = Date.now();
    // 开始与结果分别落日志，收集脚本据此区分未启动、无响应与已响应请求。
    console.log(JSON.stringify({ ...identity, kind: 'START' }));
    let response;
    try {
    response = http.post(`${base}/api/campaigns/${session.campaignId}/claim`, null, {
        headers: session.headers, tags: { name: 'POST /api/campaigns/:id/claim' }, timeout: '10s',
    });
    } catch (_) {
        console.log(JSON.stringify({ ...identity, kind: 'RESULT', httpStatus: 0, code: 'TRANSPORT_ERROR', category: 'response_unknown' }));
        outcomes.add(1, { result: 'response_unknown' });
        return;
    }
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    const order = body?.data;
    const accepted = [201, 202].includes(response.status) && body?.code === 'OK' && !!order?.orderId;
    // 受理延迟只统计成功创建订单的请求；各类拒绝和未知结果另行计数。
    if (accepted) claimLatency.add(response.timings.duration);
    const replay = response.status === 200 && order?.replayed;
    if (replay) replays.add(1);
    const category = accepted ? 'accepted' : replay ? 'replayed' : response.status === 0 || body?.code === 'RESULT_UNKNOWN' ? 'response_unknown' : response.status === 429 ? 'rate_limited'
        : response.status === 503 ? 'unavailable' : body?.code === 'OUT_OF_STOCK' ? 'out_of_stock' : 'error';
    console.log(JSON.stringify({ ...identity, kind: 'RESULT', httpStatus: response.status,
        code: body?.code || 'INVALID_RESPONSE', category, orderId: order?.orderId || null }));
    outcomes.add(1, { result: category });
    if (category === 'error') unexpected.add(1);
    check(response, { 'claim outcome is classified': () => category !== 'error' && category !== 'replayed' });
    if (!accepted) return;

    // 接口受理并不等于履约完成，轮询终态单独记录端到端耗时。
    let status = order.fulfillmentStatus;
    const maxPolls = Number(__ENV.VF_MAX_POLLS || 30);
    for (let attempt = 0; !['FULFILLED', 'FAILED'].includes(status) && attempt < maxPolls; attempt++) {
        sleep(1);
        const query = http.get(`${base}/api/me/fulfillments/${order.orderId}`, {
            headers: session.headers, tags: { name: 'GET /api/me/fulfillments/:id' }, timeout: '5s',
        });
        if (query.status === 200) status = query.json('data.fulfillmentStatus');
    }
    const outcome = status === 'FULFILLED' ? 'fulfilled' : status === 'FAILED' ? 'failed' : 'unresolved';
    terminal.add(1, { result: outcome });
    if (outcome !== 'unresolved') completionLatency.add(Date.now() - begin, { result: outcome });
}

export function teardown(sessions) {
    for (const session of sessions) {
        http.cookieJar().clear(base);
        http.post(`${base}/api/auth/logout`, null, {
            headers: session.headers, tags: { name: 'POST /api/auth/logout' }, timeout: '5s',
        });
    }
}

export function handleSummary(data) {
    return { '.local/perf/claim-result.json': JSON.stringify(data, null, 2) };
}
