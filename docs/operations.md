# 运行手册

## 依赖与配置

主应用与供应商使用独立 MySQL 数据库。MySQL 保存业务事实，Redis 只存缓存与限流计数，RocketMQ 投递已持久化事件。Compose 端口仅绑定本机回环地址。

| 配置 | 用途 |
| --- | --- |
| `VF_DB_HOST/PORT/NAME/USER/PASSWORD` | 主库连接 |
| `VF_REDIS_HOST/PORT` | 缓存与限流 |
| `VF_CACHE_ENABLED`、`VF_RATE_LIMIT_ENABLED` | 分别控制缓存与入口保护 |
| `VF_MESSAGING_ENABLED` | 启用消息客户端 |
| `VF_MESSAGING_PUBLISHING_ENABLED` | 开放 Outbox 发布 |
| `VF_MQ_ENDPOINTS` | RocketMQ gRPC Proxy 地址 |
| `VF_RECONCILIATION_BATCH_SIZE` | 对账每页订单数，默认 200，范围 1–1000 |
| `VF_SUPPLIER_ENABLED`、`VF_SUPPLIER_URL` | 供应商履约与固定路由 |
| `VF_SUPPLIER_API_TOKEN` | 主应用向供应商请求的凭证 |
| `VF_SUPPLIER_CALLBACK_SECRET` | 回调 HMAC 密钥 |
| `VF_SUPPLIER_DB_*` | 模拟器独立数据库 |

默认配置关闭外部依赖能力。本地启动脚本启用缓存和限流，`-Supplier` 启用供应商与消息。生产运行使用 HTTPS、受限数据库账号及密钥注入，保持默认安全 Cookie；`local` 配置用于本地 HTTP。

SUPPLIER 活动发布和新领取要求供应商、消息客户端与 Outbox 发布三个开关同时开启。关闭发布可用于维护已有消息，但会拒绝新发布和新领取；已生成订单仍可幂等查询返回。

## 观察与处置

`/actuator/health/liveness` 表示进程存活，`/actuator/health/readiness` 检查主库就绪。它们不承诺供应商或消息系统可用。管理员可访问 `/actuator/metrics`，关注 HTTP 延迟、连接池等待和业务指标：

| 指标 | 含义 / 处置 |
| --- | --- |
| `vf.outbox.pending` | 待投递与租约中的事件；持续增长时检查 Proxy、主题及消费组 |
| `vf.outbox.oldest.pending.seconds` | 最老待投递事件年龄；结合积压数量判断持续停滞 |
| `vf.outbox.failed` | 投递预算耗尽；调查原因后通过差异修复授予有限重投额度 |
| `vf.fulfillment.paused` | 履约预算耗尽；查单复核，不能凭超时释放库存 |
| `vf.fulfillment.oldest.reservation.seconds` | 最老预留等待时长；结合远端响应及任务状态判断 |
| `vf.reconciliation.open` | 未解决或需专项审查的差异 |
| `vf.metrics.last.refresh.epoch.seconds` | 最近成功采集，避免把陈旧指标误判为恢复 |

业务指标默认每 15 秒采集，可用 `voucherflow.metrics.interval-ms` 调整。数量统计扫描匹配状态的索引项；最老年龄通过各状态的时间索引取最早记录。积压很大时数量统计仍有成本，应降低采集频率。监控须同时检查 `vf.metrics.last.refresh.epoch.seconds`，超过三个采集周期未更新时按采集失败处理。

告警阈值依据持续时长、业务流量和基线设定。恢复后检查发送积压、消费回执、持久任务、通知与账目；Outbox 已 SENT 不代表消费完成。Broker 消费积压与死信通过管理工具单独检查。

## 依赖故障

- Redis 不可用时，公开详情使用有界数据库回源；领取与登录限流返回 503。既有订单与券查询使用数据库会话。
- RocketMQ 不可用时，事件保存在 Outbox，发送失败进入退避或 FAILED。接收方先提交消费回执与任务/通知，再 ACK。
- 供应商结果未知时保留预留，执行器优先查单。稳定请求号与冻结参数保持不变；幂等期限之后不得再发起 ISSUE。
- 进程退出后，未完成发送、履约和对账由数据库租约与游标恢复。关闭时停止抢占新工作，保留未确认事实。

回执应用逐条隔离失败，失败项延后 30 秒再处理并记录回执 ID。调度按到期时间取数，避免前面的坏回执长期占满批次；不会仅凭应用异常把供应商结果判成拒绝。

`mq-bootstrap.ps1` 创建固定主题和消费组，保留既有消费位置。新组从最早可用位置开始；消息保留窗口之外的遗漏结合原 Outbox 与回执排查，不能清空消费记录。

## 对账与修复

先检查活动本地账本，再创建活动范围的持久对账任务。扫描是逐批观察，跨系统没有共同快照。持续业务期间需要复扫；最终核对应停止新增申请并等待在途事务结束，再从头创建检查。

每次扫描固定订单 ID 上界，在首次执行时做一次完整本地校验并保存观察时间，各页复用该结果；它不是创建扫描时点的账本快照。订单按 ID 分页，失败 Outbox 按生成列 `order_id` 的索引批查；本地扫描成本随活动订单量线性增长，不再每页全活动聚合。供应商订单仍逐单查证，单批处理约 30 秒后提交已完成部分，从游标继续；处理过程中续租，失去租约即停止当前批次。

先查看 repair-preview，携带当前订单 `expectedRevision`、固定 `commandId` 与原因提交。原命令重试保持参数完全相同。版本冲突后重新复核，不修改旧命令或跳过版本检查。

允许确认发放、确认拒绝后的释放、重排查单和重投指定原事件，没有任意改库存接口。本地账目损坏、相反终态和迟到发放进入专项审查，不反转真实发放事实。人工查询和消息重投有累计授权上限，历史次数不归零。

`scripts/demo-repair.ps1` 在隔离 MySQL 和供应商 HTTP 夹具中演示差异扫描、管理员登录、重新查单预览、修复、原命令重放和库存核对，输出 `target/repair-demo/result.json`。该入口不连接本地业务库，也不开放生产数据注入接口。已持久化的冲突回执或查单返回的矛盾终态进入 `REVIEW_ONLY`；普通修复接口不会覆盖这些证据。

## 容量与验证边界

同一活动的领取持有活动行锁，吞吐取决于事务持锁时间；目前没有库存分桶。

尚未验证十万单以上对账耗时、多实例并发、长时间稳定性、Broker ACL 拒绝和非空 SENT 缺消费记录的档案恢复。现有测试及历史报告不能替代这些容量和部署验证。应用所用 Spring Boot 默认启用 HTTP 优雅停机；编排仍须给请求排空和消息运行时关闭留出退出时间。

## 备份与恢复

```powershell
.\scripts\backup-local.ps1
.\scripts\backup-local.ps1 -Service supplier-db
.\scripts\restore-local.ps1 -Backup .local/backups/<备份文件>.sql -Database vf_restore_check
```

备份存入项目 `.local/backups` 并输出 SHA-256。主库和供应商库分别备份。恢复仅创建新的 `vf_restore_` 数据库，已存在目标会被拒绝。恢复后检查迁移、活动守恒、回执关联与待恢复任务，再决定是否切换连接配置。

Flyway 已应用迁移保持不可变。升级先备份并在隔离库验证，失败时保留数据库和诊断证据，不通过删表、清库或重置迁移校验和修复启动故障。

## 数据保留

业务证据不自动清理。日志不记录密码、Cookie、CSRF token、回调签名或完整凭证；分享诊断和性能数据前脱敏。

| 数据 | 存放位置 / 保留约束 | 清理条件 |
| --- | --- | --- |
| 订单、券、库存流水、终态证据、修复回执 | 主库；覆盖业务追溯范围 | 经归档与关联核验后由运维执行，不提供在线删除接口 |
| 消费去重、原始 Outbox | 主库；至少覆盖允许重放窗口 | 先关闭该范围重放，再归档，不能单独删除去重记录 |
| 消息、死信 | Broker；受服务端存储与保留配置约束 | 到期前归档未解决死信；已过窗口转对账，不假定消息永久存在 |
| 供应商请求与凭证 | 独立供应商库；遵守幂等契约 | 原订单仍可恢复期间不得删除幂等事实 |
| 会话 | JDBC Session | 由会话过期机制清理，不手工改业务表 |
| 备份、诊断、性能样本 | 项目 `.local/` | 确认可恢复、证据已归档且不再用于对照后清理；凭证夹具及时删除 |

## 容器运行

验证通过后构建运行镜像：

```powershell
docker build -t voucherflow:local .
docker build -t voucherflow-supplier:local ./supplier-simulator
```

两个镜像使用固定摘要的 Java 21 JRE，以非 root 用户运行，只复制已构建的 JAR。通过环境变量注入数据库、Redis、消息代理与供应商地址，不在镜像中放入 `.env`。容器连接地址必须在其网络中可达；面向宿主机 JVM 的 Compose 消息代理配置不能直接用于容器内客户端。

使用完整 Linux 容器链路时，先停止宿主机上的主应用与供应商 JVM，释放 `8080` 和 `8090`。依赖数据继续使用同一 Compose 项目和 `.local` 挂载目录：

```powershell
.\scripts\verify.ps1
.\scripts\container-up.ps1 -Build -Faults
.\scripts\demo-flow.ps1
.\scripts\demo-supplier.ps1
```

`deploy/compose.container.yaml` 提供 `linux` profile，明确列出两个应用各自需要的环境变量。主应用访问容器内 MySQL、Redis、Broker 与供应商，供应商回调访问 `app:8080`。`broker-container.conf` 宣告容器网络中的 `broker` 地址；宿主机 JVM 配置继续使用原有 `broker.conf`。两个模式不能混用同一 Broker 的客户端路由。

启动脚本的 `UP` 只证明应用探针可用。`demo-flow` 进一步验证登录、领取、核销与库存，`demo-supplier` 验证消息登记、HTTP 发放、丢响应后的查询恢复、明确拒绝释放和最终对账；后者需要显式 `-Faults`。普通容器运行省略 `-Faults`。

结束容器应用并切回宿主机运行：

```powershell
docker compose --env-file .env -f compose.yaml -f deploy/compose.container.yaml --profile linux stop app supplier
.\scripts\dev-up.ps1 -Supplier
```

切换会恢复 Broker 的宿主机路由，保留 MySQL、Redis 和消息存储。不要同时保留使用另一种路由的应用实例。

## 死信归档与重放

```powershell
.\scripts\mq-dlq.ps1 -Action Export -Group vf_notification_worker -Archive .local/dlq/notifications.jsonl
.\scripts\mq-dlq.ps1 -Action Replay -Archive .local/dlq/notifications.jsonl
```

导出保留消费组、原生消息 ID、业务正文、SHA-256 和归档时刻，不移动消费位点。单个档案限制为 1–1000 条，已存在路径不可覆盖；使用 `BeginEpochMillis` 和 `EndEpochMillis` 缩小范围。处理失败原因后再重放，工具验证摘要、协议与消费组，并保留原 eventId 和正文。归档重放窗口为七天，超窗先对账，不通过修改档案时刻绕过限制。
