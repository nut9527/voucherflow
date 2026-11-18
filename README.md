<h1 align="center">VoucherFlow</h1>

<p align="center">
  限量权益发放与核销服务
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-4.1.2-6DB33F?style=flat-square" alt="Spring Boot 4.1.2">
  <img src="https://img.shields.io/badge/MySQL-8.4-4479A1?style=flat-square" alt="MySQL 8.4">
  <img src="https://img.shields.io/badge/Redis-7.4.2-DC382D?style=flat-square" alt="Redis 7.4.2">
  <img src="https://img.shields.io/badge/RocketMQ-5.3.2-D77310?style=flat-square" alt="RocketMQ 5.3.2">
</p>

<p align="center">
  <a href="#功能特性">功能特性</a> ·
  <a href="#项目架构">项目架构</a> ·
  <a href="#技术栈">技术栈</a> ·
  <a href="#项目结构">项目结构</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#测试验证">测试验证</a> ·
  <a href="#文档">文档</a> ·
  <a href="#开源协议">开源协议</a>
</p>

VoucherFlow 覆盖活动发布、权益领取、库存预留、发放、核销与对账全流程。支持 **本地事务发放** 和 **供应商异步履约**，以库存流水、可靠消息和持久任务串联完整业务链路。

## 功能特性

| 功能           | 实现                                                                         |
| -------------- | ---------------------------------------------------------------------------- |
| **活动管理**   | 草稿编辑、发布、停用，配置领取窗口、核销期限与履约方式                       |
| **库存与幂等** | 事务预留与唯一流水维护库存守恒；重复领取返回原订单，重复核销返回原回执       |
| **双模式发放** | 本地事务直接生成券；供应商模式支持幂等请求、主动查单与签名回调               |
| **可靠消息**   | Transactional Outbox、消费去重、租约恢复、重试与死信重放，驱动履约和站内通知 |
| **对账修复**   | 本地账本核对、跨系统差异扫描、修复预览、版本校验与幂等审计回执               |
| **访问保护**   | 数据库会话、角色授权、CSRF、Redis Lua 限流、活动缓存与有界回源               |
| **运行观测**   | 请求追踪、健康探针、业务指标、故障演练与 k6 性能采样                         |

## 项目架构

主应用采用按业务域分包的 **模块化单体**，Controller → Service → Mapper / Store 分层，由 ArchUnit 校验依赖边界。供应商模拟器独立运行，拥有自己的数据库。

```mermaid
flowchart TB
    Client["用户 / 管理员"] --> API["HTTP API · 认证与限流"]
    API --> Core["活动 · 库存 · 订单 · 券包 · 核销"]
    API --> Redis[("Redis · 缓存与限流")]
    Core --> DB[("MySQL · 业务数据与 Outbox")]
    DB --> Publisher["Outbox 发布器"]
    Publisher --> MQ["RocketMQ"]
    MQ --> Worker["持久履约任务"]
    MQ --> Notice["站内通知"]
    Notice --> DB
    Worker -->|"发放 / 查单"| Supplier["供应商模拟器"]
    Supplier --> SupplierDB[("供应商 MySQL")]
    Supplier -->|"响应 / 签名回调"| Evidence["结果存证与终态确认"]
    Evidence --> DB
```

### 两种履约模式

| 模式       | 处理链路                                                                                 |
| ---------- | ---------------------------------------------------------------------------------------- |
| `LOCAL`    | 领取 → 预留库存 → 确认发放 → 生成券，在同一个数据库事务中完成                            |
| `SUPPLIER` | 领取与预留 → Outbox → 消息登记持久任务 → 供应商发放 → 查单 / 回调确认 → 生成券或释放预留 |

### 核心设计

- **库存有账可查**：`总库存 = 可用库存 + 预留库存 + 已发放库存`。活动行锁串行化库存变更，每次预留、确认和释放都有唯一流水。
- **履约可恢复**：订单、预留与事件同事务提交，消费者先登记持久任务再 ACK，执行器通过租约续跑。结果未知时保留预留，由查单和回调确认终态。
- **修复可追溯**：扫描进度与差异持久化；确认、释放前重新查单取证，修复命令通过版本校验与幂等回执记录执行结果。

## 技术栈

| 层面       | 技术                                                       |
| ---------- | ---------------------------------------------------------- |
| 应用       | Java 21 · Spring Boot 4.1.2 · Spring MVC · Bean Validation |
| 数据       | MySQL 8.4 · MyBatis Starter 4.1.0 · Spring JDBC · Flyway   |
| 缓存与消息 | Redis 7.4.2 · Lua · RocketMQ 5.3.2 / Java Client 5.2.2     |
| 安全       | Spring Security · Spring Session JDBC                      |
| 监控       | Spring Boot Actuator · Micrometer                          |
| 测试       | JUnit Jupiter · Testcontainers · ArchUnit · k6             |
| 构建与部署 | Maven Wrapper · Docker Compose · PowerShell 7              |

## 项目结构

```text
VoucherFlow/
├── src/main/
│   ├── java/com/example/voucherflow/
│   │   ├── campaign/          # 活动管理与缓存
│   │   ├── inventory/         # 库存与流水
│   │   ├── issuance/          # 领取与订单
│   │   ├── fulfillment/       # 履约任务与供应商接入
│   │   ├── redemption/        # 券包与核销
│   │   ├── outbox/            # 事件发布、消费与重放
│   │   ├── notification/      # 站内通知
│   │   ├── reconciliation/    # 对账与修复
│   │   ├── identity/          # 身份与权限
│   │   ├── protection/        # 限流与准入
│   │   ├── common/            # 响应、错误、分页与追踪
│   │   └── infrastructure/    # 调度、消息运行时与指标
│   └── resources/
│       ├── db/migration/      # Flyway 数据迁移
│       ├── mapper/            # MyBatis XML
│       └── redis/             # Lua 脚本
├── src/test/                  # 单元、集成、架构与恢复测试
├── supplier-simulator/        # 独立供应商服务与协议测试
├── scripts/                   # 启动、演示、验证、诊断与备份
├── perf/                      # k6 详情查询与领取场景
├── deploy/                    # 容器与 RocketMQ 配置
├── docs/                      # API、运行手册与供应商协议
├── compose.yaml               # 本地依赖编排
├── Dockerfile                 # 主应用镜像
├── .env.example               # 环境变量模板
└── pom.xml                    # 主应用构建与依赖
```

## 快速开始

启动需要 Windows、JDK 21 和 Docker Desktop（Linux 容器）。启停入口使用 Windows 自带的 PowerShell 5.1，无需安装 PowerShell 7；演示、测试与运维脚本使用 PowerShell 7。

```powershell
.\start.cmd           # 构建并启动全部服务
.\start.cmd -Faults   # 同时开放供应商故障演示接口
.\stop.cmd            # 停止服务，保留数据
```

入口自动生成 `.env`、打包两个应用、启动依赖并等待就绪。默认开启缓存、限流、消息、通知和供应商履约。支持双击，执行结束后按键关闭窗口；服务在后台运行。

- 主应用：[就绪检查](http://localhost:8080/actuator/health/readiness)
- 供应商：[健康检查](http://localhost:8090/actuator/health)
- 演示账号：`admin-demo`、`user-demo`，密码见 `.env`。

项目提供 HTTP API，没有前端首页。运行环境配置与日志命令见[运行手册](docs/operations.md)。

## 测试验证

在 PowerShell 7 中执行；若脚本受签名策略限制，使用 `pwsh -NoProfile -ExecutionPolicy Bypass -File <脚本> <参数>`。

```powershell
.\scripts\verify.ps1                  # 编译、单元/集成测试、格式检查、打包
.\scripts\verify.ps1 -Suite Messaging # RocketMQ 发送、消费和发布故障
.\scripts\verify.ps1 -Suite Recovery  # 进程中断恢复及跨进程协作
.\scripts\verify.ps1 -Suite Repair    # HTTP 对账修复与幂等回放
.\scripts\format.ps1 -Check           # 格式检查；不加 -Check 则格式化
.\scripts\demo-flow.ps1               # 本地领取、核销、库存对账
.\scripts\demo-supplier.ps1           # 供应商故障恢复；启动时需 -Faults
```

启动只打包，不执行测试。Java 排版由 Spotless/google-java-format 统一，配置见两个模块的 `pom.xml`；已应用的 Flyway 迁移保持不变。

## 文档

- [API 参考](docs/api.md)：认证、接口、状态与错误码。
- [供应商协议](docs/supplier-contract.md)：幂等请求、查单、签名回调与故障控制。
- [运行手册](docs/operations.md)：环境配置、监控、备份恢复、死信重放与性能采样。

## 开源协议

本项目原创内容采用 [MIT License](LICENSE)，版权署名为 `2026 VoucherFlow contributors`。

Maven Wrapper 等第三方组件遵循各自的许可证和版权声明。
