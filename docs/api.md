# API 参考

接口基址为应用地址下的 `/api`。HTTP 客户端通过 Cookie 携带会话，管理员与普通用户分别维护自己的 Cookie 和 CSRF token。

## 通用约定

- 普通接口返回 `{ "code": "OK", "message": "成功", "data": ..., "traceId": "..." }`；错误的 `data` 为 `null`，HTTP 状态与错误码同时表达结果。
- `X-Trace-Id` 响应头与普通响应体中的 `traceId` 一致，由服务端为每个请求生成。
- 实体 ID 在 JSON 中使用字符串，路径 ID 使用正整数。时间使用 ISO 8601 UTC 字符串，以 `Z` 结尾，数据库精度为毫秒。
- 列表使用 `afterId` 和 `size`，默认值分别为 `0`、`20`。`afterId >= 0`，`1 <= size <= 100`，按 ID 升序返回。
- 分页结构为 `{ "items": [...], "nextCursor": "..." }`。下一次请求将 `nextCursor` 传入 `afterId`；`nextCursor=null` 表示没有后续页。
- 写请求必须携带当前会话的 CSRF token，包括登录与登出。

## 身份与会话

| 方法 | 路径 | 访问条件 | 返回数据 |
| --- | --- | --- | --- |
| GET | `/auth/csrf` | 公开 | `headerName`、`parameterName`、`token` |
| POST | `/auth/login` | 公开，需要 CSRF | `userId`、`username`、`role` |
| GET | `/auth/me` | 已登录 | `userId`、`username`、`role` |
| POST | `/auth/logout` | 当前会话，需要 CSRF | `null` |

登录顺序：

1. 请求 `GET /api/auth/csrf`，保存响应 Cookie 与 `data.token`。
2. 使用同一 Cookie 会话提交 `POST /api/auth/login`，内容类型为 `application/x-www-form-urlencoded`，表单字段为 `username`、`password`。将 token 放入 `data.headerName` 指定的请求头。
3. 登录成功后重新请求 `/api/auth/csrf`，保存更新后的 token；后续写请求使用它。
4. 登出后旧会话失效；再次登录前重新获取 CSRF token。

会话空闲期限为 30 分钟。`USER` 和 `ADMIN` 为不同职责：普通用户领取和查询本人记录，管理员管理活动、核销和对账。

## 活动

| 方法 | 路径 | 权限 | 成功状态 / 返回数据 |
| --- | --- | --- | --- |
| GET | `/campaigns` | 公开 | 200，公开活动分页 |
| GET | `/campaigns/{id}` | 公开 | 200，公开活动详情 |
| GET | `/admin/campaigns` | ADMIN | 200，管理活动分页 |
| GET | `/admin/campaigns/{id}` | ADMIN | 200，管理活动详情 |
| POST | `/admin/campaigns` | ADMIN | 201，`{ "id": "..." }` |
| PUT | `/admin/campaigns/{id}` | ADMIN | 200，更新后的管理详情 |
| POST | `/admin/campaigns/{id}/publish` | ADMIN | 200，发布后的管理详情 |
| POST | `/admin/campaigns/{id}/disable` | ADMIN | 200，停用后的管理详情 |

创建与编辑使用相同的 JSON 字段：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `name` | string | 必填，非空白，最多 128 字符 |
| `description` | string / null | 最多 2000 字符；空值保存为空字符串 |
| `totalStock` | integer | 1～1,000,000,000 |
| `startAt` | UTC Instant | 必填，领取开始，包含此时刻 |
| `endAt` | UTC Instant | 必填，领取结束，不包含此时刻 |
| `redeemEndAt` | UTC Instant | 必填，核销截止，不包含此时刻 |
| `fulfillmentMode` | string | `LOCAL` 或 `SUPPLIER` |
| `supplierId`、`productCode`、`contractVersion` | string / null | `LOCAL` 必须为空；`SUPPLIER` 必须完整配置 |

窗口满足 `startAt < endAt <= redeemEndAt`。`SUPPLIER` 发布需要启用供应商和消息能力，并匹配服务端登记的供应商与协议。模拟器对应 `supplierId=simulator`、`contractVersion=v1`，商品代码由活动指定。

公开列表项包含 `id`、`name`、`totalStock`、`status`、`phase`、`startAt`、`endAt`；详情增加 `description`、`redeemEndAt`、`fulfillmentMode`。`phase` 为 `NOT_STARTED`、`CLAIMABLE`、`ENDED` 或 `DISABLED`，表示时间和活动状态，不保证还有库存。

管理详情包含 `id`、`name`、`description`、`totalStock`、`availableStock`、`reservedStock`、`issuedStock`、`status`、三个窗口时间、履约模式及配置、`createdBy`、`createdAt`、`updatedAt`。公开接口不返回剩余库存与管理信息。

创建产生 `DRAFT`，草稿仅管理入口可见。只有草稿可以编辑；发布变为 `ACTIVE` 并冻结配置。重复发布 `ACTIVE` 活动返回原资源。停用将 `ACTIVE` 变为 `DISABLED`，重复停用返回原资源；停用不删除历史订单、券或流水。

## 领取与订单

| 方法 | 路径 | 权限 | 成功状态 / 返回数据 |
| --- | --- | --- | --- |
| POST | `/campaigns/{id}/claim` | USER | LOCAL 首次 201，SUPPLIER 首次 202，重放 200 |
| GET | `/me/fulfillments` | USER | 200，本人订单分页 |
| GET | `/me/fulfillments/{id}` | USER | 200，本人订单详情 |
| GET | `/me/fulfillments/by-campaign/{campaignId}` | USER | 200，本人在该活动的订单 |
| GET | `/admin/fulfillments/{id}` | ADMIN | 200，订单详情 |

领取不需要请求体，不接受客户端指定用户身份。领取结果字段为 `orderId`、`campaignId`、`fulfillmentStatus`、`voucherId`、`replayed`。`LOCAL` 成功结果为 `FULFILLED`，并返回券 ID。

订单详情及列表项包含 `orderId`、`campaignId`、`campaignName`、`fulfillmentStatus`、`voucherId`、`expiresAt`、`createdAt`、`completedAt`。

`SUPPLIER` 的 202 表示预留与申请已持久化。客户端查询原订单等待 `FULFILLED` 或 `FAILED`；`PENDING` 和 `UNKNOWN` 没有可核销券。查询暂不存在、HTTP 故障或执行预算耗尽均不构成失败发放证据。失败订单保留，原用户不能对同一活动重新申请。

同一用户在同一活动只能产生一单一券。重复请求返回相同的订单和券，`replayed=true`，不再次扣减库存；已有成功订单不受活动售罄、停用或结束影响。

如果领取响应丢失，可通过活动 ID 查询原订单。`RESULT_UNKNOWN` 表示本次请求无法确认提交结果，应查询原单；一次查询 404 仅表示尚未观察到已提交记录。本人接口查询其他用户记录返回 404。

## 券包与核销

| 方法 | 路径 | 权限 | 成功状态 / 返回数据 |
| --- | --- | --- | --- |
| GET | `/me/vouchers` | USER | 200，本人券分页 |
| GET | `/me/vouchers/{id}` | USER | 200，本人券详情 |
| GET | `/admin/vouchers/{id}` | ADMIN | 200，券详情 |
| POST | `/vouchers/{id}/redeem` | ADMIN | 200，核销回执 |

券详情及列表项包含 `id`、`orderId`、`campaignId`、`campaignName`、`status`、`issuedAt`、`expiresAt`、`usedAt`。展示状态为 `UNUSED`、`USED` 或 `EXPIRED`，`usedAt` 在未核销时为 `null`。

核销无需请求体。回执字段为 `id`、`voucherId`、`operatorId`、`createdAt`、`replayed`。首次核销要求券未使用且未到期；重复核销返回首次回执，保留原操作人和时间，`replayed=true`，即使此时已超过券的有效期也不改变既有结果。未核销的过期券返回 `409 VOUCHER_EXPIRED`。

## 活动对账

`GET /api/admin/campaigns/{id}/stats`，仅限 ADMIN。

返回 `campaignId`、四项库存、`pendingCount`、`unknownCount`、`fulfilledCount`、`failedCount`、`voucherCount`、`usedCount`、`redemptionCount`、`reserveCount`、`confirmCount`、`releaseCount`、`brokenLinks`、`differences`、`consistent`、`observedAt`。

`differences` 核对库存守恒、订单状态、券数、库存流水累计值和已用券回执数。所有差额为零且 `brokenLinks=0` 时，`consistent=true`。查询在独立只读快照内完成，不修改库存或修复历史记录。

## 通知

`GET /api/me/notifications` 仅 USER 可用，使用通用游标分页。每项包含 `id`、`orderId`、可空 `voucherId`、`channel=IN_APP`、`content` 和 `createdAt`。结果只属于当前用户，发放失败的通知没有券 ID。通知来自业务事件的幂等消费，允许晚于订单终态到达。

## 跨系统对账与修复

以下接口均为 ADMIN，写操作必须携带 CSRF token。

| 方法 | 路径 | 结果 |
| --- | --- | --- |
| POST | `/admin/reconciliation/runs` | 请求 `{ "campaignId": 1 }`，202 返回持久任务 |
| GET | `/admin/reconciliation/runs/{id}` | 扫描状态、截止范围、游标及已扫描数量 |
| GET | `/admin/reconciliation/runs/{id}/diffs` | 通用分页查询差异及脱敏证据 |
| GET | `/admin/reconciliation/diffs/{id}/repair-preview` | 重新取证后的订单版本、远端结果及允许操作 |
| POST | `/admin/reconciliation/diffs/{id}/repair` | 提交修复命令，200 返回固定回执 |

创建扫描时短暂锁定活动，等待该活动已经受理的领取事务提交，并冻结当时最大的订单 ID 为 `upperOrderId`。任务只分页观察该范围内的订单；随后创建的订单属于下一次扫描。`cutoffAt` 记录观察开始时刻，扫描过程持续保存进度，不构成跨数据库或跨供应商的统一快照。升级时已有扫描的上界由该活动现存订单回填，恢复后按此固定范围继续。

任务状态为 `READY`、`RUNNING`、`COMPLETED`。完成扫描不意味着差异已解决。差异状态为 `OBSERVING`、`OPEN`、`NEEDS_RECHECK`、`RESOLVED` 或 `REVIEW_ONLY`。

修复请求：

```json
{
  "commandId": "2e6a292d-73a3-4c48-80e8-86ce7640c7c8",
  "expectedRevision": 7,
  "operation": "REQUERY",
  "reason": "供应商连接恢复，重新查询原申请"
}
```

`commandId` 是本次操作意图的 UUID，原请求重试必须保留全部参数。`CONFIRM` 和 `RELEASE` 需要新查单所得、与原订单关联一致的成功或明确拒绝证据。`REQUERY` 只给暂停的未决订单增加一次查询额度，累计最多三次；`REPLAY_OUTBOX` 只重排差异绑定的 FAILED 原事件，每次增加三次投递额度，累计最多六次。重排返回 `NEEDS_RECHECK`，不会直接声称差异已解决。

回执包含 `commandId`、`diffId`、`orderId`、`operation`、结果 `revision` 和 `diffState`。相同命令同参数返回原回执；参数变化、旧版本或不允许的状态返回 409。

## 状态与错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 参数、分页或窗口不合法 |
| 401 | `UNAUTHENTICATED` / `BAD_CREDENTIALS` | 未登录或登录凭证错误 |
| 403 | `FORBIDDEN` / `CSRF_INVALID` | 角色不符或 CSRF 凭证无效 |
| 404 | `NOT_FOUND` | 资源不存在，或本人接口无权查看该资源 |
| 405 | `METHOD_NOT_ALLOWED` | 请求方法不受支持 |
| 409 | `INVALID_CAMPAIGN_STATE` | 活动状态不允许当前操作 |
| 409 | `CAMPAIGN_NOT_STARTED` / `CAMPAIGN_ENDED` / `CAMPAIGN_DISABLED` | 新领取不满足活动条件 |
| 409 | `OUT_OF_STOCK` / `VOUCHER_EXPIRED` | 无可领取库存或券已过期 |
| 409 | `REVISION_CONFLICT` / `COMMAND_CONFLICT` / `REPAIR_NOT_ALLOWED` | 修复版本、命令身份或证据不满足条件 |
| 409 | `SUPPLIER_EVIDENCE_CONFLICT` | 同一供应商回执标识出现不同内容 |
| 429 | `RATE_LIMITED` | 达到入口额度，按 `Retry-After` 重试 |
| 503 | `RATE_LIMIT_UNAVAILABLE` | 限流依赖不可用，按 `Retry-After` 重试 |
| 503 | `SYSTEM_BUSY` | 暂时无法执行，或供应商受理关闭、未决容量达到上限；已有原单仍可重放 |
| 503 | `RESULT_UNKNOWN` | 提交结果待确认，按活动查询原单 |
| 500 | `INTERNAL_ERROR` | 未预期的服务异常，可用 traceId 定位 |

`GET /actuator/health/liveness` 与 `GET /actuator/health/readiness` 为公开探针，使用 Actuator 响应结构；正常返回 `{"status":"UP"}`。`GET /actuator/health` 仅管理员可访问。
