# 供应商协议

供应商模拟器是独立 HTTP 服务，使用独立 MySQL 数据库。主服务仅通过公开协议发放和查询，不读取供应商数据库。

## 请求与结果

业务请求使用 `Authorization: Bearer <token>`。令牌来自服务端配置，不接受活动请求提供任意供应商地址。

| 接口 | 输入 | 语义 |
| --- | --- | --- |
| `POST /supplier/fulfillments` | `requestNo`、`productCode`、`subjectRef` | 同号同参数返回持久结果；不同参数返回 409 |
| `GET /supplier/fulfillments/{requestNo}` | 原始请求号 | 查询原业务，不产生新发放 |

结果字段为 `requestNo`、`productCode`、`subjectRef`、`status`、`resultVersion`、`voucherRef`。所有结果使用 HTTP 200；通信错误使用相应 HTTP 错误码。

| status | 版本与凭证 | 平台处理 |
| --- | --- | --- |
| `NOT_FOUND` | 版本、凭证均为空 | 保留预留，按冻结窗口决定原号重发或继续查询 |
| `ACCEPTED` | 正整数版本，无凭证 | 保留预留并查询 |
| `SUCCEEDED` | 正整数版本，非空凭证 | 确认库存并生成一张券 |
| `REJECTED` | 正整数版本，无凭证 | 供应商承诺未发放且不会再发放，释放预留 |

超时、5xx、响应格式错误及重试耗尽都不代表 `REJECTED`。平台将未决订单保留为 `UNKNOWN`；自动预算耗尽后任务暂停，库存仍被预留。

主服务冻结供应商标识、协议、商品、主体引用、请求指纹及幂等截止。自动和人工操作均不得越过冻结截止再次 ISSUE。模拟器保留幂等记录，窗口之后仍可查询。人工重查只提供额外 QUERY 信用，不重置自动尝试历史，也不授权 ISSUE。

请求指纹依次编码 `productCode` 和 `subjectRef`：每项先写四字节大端 UTF-8 字节长度，再写 UTF-8 内容，最终取 SHA-256 小写十六进制。`课程` 和 `user:1` 的指纹为 `c67cfc039fefc1f573be4f0f875a2da522bbfd0c2f6f66f133c3d5112076b62f`。

## 回调

平台接收 `POST /api/supplier/callbacks`，body 为结果字段加 `eventId`。供应商在发放事务内持久化回调任务；重投保留 eventId、版本和 body，只更新签名时间戳。

- `X-Supplier-Timestamp`：Unix 秒数，允许与平台时钟相差五分钟。
- `X-Supplier-Signature`：`HMAC-SHA256(secret, timestamp + "." + rawBody)`，小写十六进制。
- 回调密钥独立于 API 令牌；平台对原始 body 验签，再验证订单、商品、主体和结果形状。
- 平台成功响应表示证据已经持久化，后台独立应用。相同回执键换内容被拒绝；同版本矛盾或终态反转保留冲突，不修改库存。

## 模拟器故障控制

仅在 `VF_SUPPLIER_FAULTS_ENABLED=true` 时开放 `PUT /supplier/faults`，沿用供应商 API 令牌鉴权。body 为 `{"dropNextResponse":true,"pauseCallbacks":true}`：下一次发放在提交后返回 503，并暂停回调，用于验证查询恢复。发送两个 false 恢复正常行为。商品 `reject` 产生明确拒绝，其余商品产生成功凭证。

对外业务采用本地平台核销。模拟器并不实现真实商业供应商结算或第三方核销。
