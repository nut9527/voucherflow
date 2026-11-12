// 以固定到达速率轮询公开活动详情，记录成功率、成功延迟与响应分类。
import http from 'k6/http';
import { check } from 'k6';
import execution from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = (__ENV.VF_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const ids = (__ENV.VF_CAMPAIGN_IDS || '').split(',').filter(Boolean);
if (!ids.length || ids.some(id => !/^[1-9]\d*$/.test(id))) {
    throw new Error('VF_CAMPAIGN_IDS must contain published campaign IDs');
}
const success = new Rate('detail_success');
const latency = new Trend('detail_success_duration', true);
const outcomes = new Counter('detail_outcomes');

export const options = {
    summaryTrendStats: ['avg', 'min', 'max', 'p(50)', 'p(95)', 'p(99)'],
    scenarios: {
        detail: {
            executor: 'constant-arrival-rate', rate: Number(__ENV.VF_RATE || 50), timeUnit: '1s',
            duration: __ENV.VF_DURATION || '30s', preAllocatedVUs: Number(__ENV.VF_VUS || 10),
            maxVUs: Number(__ENV.VF_MAX_VUS || 50), gracefulStop: '10s',
        },
    },
    thresholds: { detail_success: [`rate>=${__ENV.VF_MIN_SUCCESS || '0.99'}`], dropped_iterations: ['count==0'] },
    discardResponseBodies: false,
};

export default function () {
    // 在给定活动间轮转，使每轮采样使用同一组活动。
    const id = ids[execution.scenario.iterationInTest % ids.length];
    const response = http.get(`${base}/api/campaigns/${id}`, {
        timeout: '5s', tags: { name: 'GET /api/campaigns/:id' },
    });
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    const valid = response.status === 200 && body?.code === 'OK' && body?.data?.id === id;
    success.add(valid);
    // 仅成功详情响应进入延迟分布，失败仍计入成功率和结果分类。
    if (valid) latency.add(response.timings.duration);
    const result = valid ? 'success' : response.status === 503 ? 'busy'
        : response.status === 404 ? 'not_found' : 'error';
    outcomes.add(1, { result });
    check(response, { 'public detail is valid': () => valid });
}

export function handleSummary(data) {
    return { '.local/perf/detail-result.json': JSON.stringify(data, null, 2) };
}
