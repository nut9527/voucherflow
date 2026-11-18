# 运行手册

## 启停与环境

`start.cmd` 自动初始化 `.env`、打包主应用和供应商模拟器，再启动完整容器链路。再次运行会应用代码变更，保留已有凭证、数据库和消息存储。`stop.cmd` 停止项目容器，保留数据。

启停入口共用 `scripts/launch.cmd` 和 `scripts/app.ps1`，使用 Windows 自带的 PowerShell 5.1；演示、测试与运维脚本使用 PowerShell 7。

入口仅对子进程设置 `ExecutionPolicy Bypass`，不修改全局策略。双击执行后会等待按键；自动化调用设置进程环境变量 `VF_NO_PAUSE=1` 可跳过等待。组织组策略强制签名时仍需使用认可的签名脚本。

默认启用所有业务能力；`start.cmd -Faults` 额外开放故障演示接口，重新运行 `start.cmd` 可关闭。启动前释放宿主机的 `8080`、`8090` 端口。

```powershell
docker compose --env-file .env -f compose.yaml -f deploy/compose.container.yaml --profile linux logs -f --tail 100 app supplier
```

| 配置 | 用途 |
| --- | --- |
| `VF_DB_*`、`VF_SUPPLIER_DB_*` | 主库与供应商独立数据库 |
| `VF_REDIS_HOST/PORT` | 缓存与限流 |
| `VF_CACHE_ENABLED`、`VF_RATE_LIMIT_ENABLED` | 缓存和入口保护开关 |
| `VF_MESSAGING_ENABLED`、`VF_MESSAGING_PUBLISHING_ENABLED` | 消息客户端和 Outbox 发布 |
| `VF_MQ_ENDPOINTS` | RocketMQ gRPC Proxy 地址 |
| `VF_SUPPLIER_ENABLED`、`VF_SUPPLIER_URL` | 供应商履约开关与地址 |
| `VF_SUPPLIER_API_TOKEN`、`VF_SUPPLIER_CALLBACK_SECRET` | 请求凭证与回调签名密钥 |
| `VF_RECONCILIATION_BATCH_SIZE` | 对账批次大小，默认 200，范围 1–1000 |

容器配置位于 `deploy/compose.container.yaml`，通过环境变量启用完整能力。主应用访问容器内 MySQL、Redis、Broker 和供应商，回调访问 `app:8080`。宿主机调试时使用 `deploy/rocketmq/broker.conf`；容器路由使用 `broker-container.conf`，不能混用。`local` profile 允许 HTTP Cookie，生产使用 HTTPS 与密钥注入。

## 健康与故障

`/actuator/health/liveness` 检查进程存活，`/actuator/health/readiness` 检查主库就绪。供应商探针为 `:8090/actuator/health`。探针不代表完整业务链路通过；使用 `demo-flow.ps1`、`demo-supplier.ps1` 验证领取、发放、核销及对账。

管理员通过 `/actuator/metrics` 查看指标：

| 指标 | 检查内容 |
| --- | --- |
| `vf.outbox.pending`、`vf.outbox.oldest.pending.seconds` | 待投递数量与最老事件年龄 |
| `vf.outbox.failed` | 投递预算耗尽，需检查原因后重投 |
| `vf.fulfillment.paused`、`vf.fulfillment.oldest.reservation.seconds` | 暂停任务与最老预留年龄 |
| `vf.reconciliation.open` | 待处理差异 |
| `vf.metrics.last.refresh.epoch.seconds` | 指标是否仍在刷新；默认每 15 秒更新 |

- Redis 故障时，详情查询有界回源；登录和领取限流返回 503。
- RocketMQ 故障时，事件留在 Outbox 中退避重试。消费者提交持久任务或通知后才 ACK。
- 供应商结果未知时保留预留，优先查单；超出幂等期限后不重新发起 ISSUE。
- 进程恢复后依靠数据库租约和游标继续任务。回执应用失败后延迟 30 秒重试。

`mq-bootstrap.ps1` 创建主题和消费组，保留既有消费位置。Outbox 已 SENT 不代表消费完成，需结合消费回执、积压和业务账目判断。

## 对账与修复

先核对活动本地账本，再创建持久对账任务。扫描冻结订单 ID 上界，按批记录差异；持续业务期间需要复扫，最终核对前停止新增申请并等待在途事务结束。

修复先调用 `repair-preview`，再提交 `expectedRevision`、固定 `commandId` 和原因。版本冲突需重新取证；重复命令返回原审计回执。矛盾回执与终态冲突进入 `REVIEW_ONLY`，不覆盖已有事实。

```powershell
.\scripts\verify.ps1 -Suite Repair
```

该验证使用隔离测试库，结果写入 `target/repair-demo/result.json`。单活动库存仍依赖行锁串行化，尚未验证十万单对账、多实例和长期稳定性。

## 备份与恢复

```powershell
.\scripts\backup-local.ps1
.\scripts\backup-local.ps1 -Service supplier-db
.\scripts\restore-local.ps1 -Backup .local/backups/<备份文件>.sql -Database vf_restore_check
```

备份保存在 `.local/backups`，附带 SHA-256。恢复只创建新的 `vf_restore_` 数据库；恢复后核对迁移、库存守恒、订单关联和待处理任务。成对快照与恢复使用 `snapshot-pair.ps1`、`restore-pair.ps1`。

Flyway 已应用迁移不可修改。业务证据与消息数据不随停机清理；升级失败时保留数据库和诊断信息。日志与对外诊断文件不得包含密码、会话、回调签名或完整凭证。

## 死信与性能采样

```powershell
.\scripts\mq-dlq.ps1 -Action Export -Group vf_notification_worker -Archive .local/dlq/notifications.jsonl
.\scripts\mq-dlq.ps1 -Action Replay -Archive .local/dlq/notifications.jsonl
```

死信导出不移动消费位点。单个归档为 1–1000 条；重放校验摘要、协议、消费组和七天窗口，保留原 eventId。超窗事件先对账。

性能入口为 `measure-detail.ps1`、`measure-claim.ps1`，结果位于 `.local/perf`。详情采样记录缓存回源增量；领取采样保留响应丢失、结果未知及未启动输入，随后补查订单和账本。`collect-diagnostics.ps1` 收集健康探针、容器资源及指定 JVM 的诊断信息。
