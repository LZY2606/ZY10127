# Battery Lab（电池协议实验室）

一个**不控制真实电源**的本地应用：用**可控虚拟时钟**执行测试协议、接收设备测点、
注入掉线 / 丢应答 / 重启 / 设备故障，并让研究员逐周期复核**容量、库仑效率与中断原因**。

- 后端：Spring Boot 3.5、Spring Data JPA
- 存储：SQLite（文件 `batterylab.db`，可用 `sqlite3` 直接查看）
- 前端：原生单页时线（协议图 / 当前步骤 / 原始测点 / 派生周期边界）
- 设备：纯函数式、**确定性**的虚拟设备（无随机数、无墙钟参与计算）

## 构建与运行

```bash
mvn -q -DskipTests package

mvn -q test
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5327
# 浏览器打开
open http://127.0.0.1:5327
```

种子数据在启动时写入（幂等）：

| 类别 | 名称 | 版本 | 说明 |
| --- | --- | --- | --- |
| 协议 `lab-standard` | 1.0.0 | CC 充电 → CV 截止 → 静置 → CC 放电 → 静置 → 循环跳转 ×3 |
| 设备能力 `generic-channel` | 1.0.0 | 量程、采样周期、可上报字段 |
| 计算规则 `capacity-rules` | 1.0.0 / 2.0.0 | 方向号约定、端点归属、死区、积分法、温度门限 |

协议、设备能力、计算规则各自独立版本化；创建运行时把三者的**确切版本与协议快照**
冻结到运行上，之后目录里再新增版本不会改变历史运行。

## 页面能看到什么

- **协议图**：每个步骤一个节点，当前步骤高亮、已离开步骤标绿。
- **当前步骤与运行状态**：虚拟时钟、周期号、最后确认 seq、链路状态、中断原因。
- **原始测点**：只追加的表格，迟到行黄底、采用修正行蓝底。
- **派生周期边界**：每个派生版本一张表（充电量 / 放电量 / 库仑效率 / 中断原因 /
  排除区间数 / 原始测点数）与复核标记。
- **冲突卡片**：重复序号不同载荷时出现，提供「保留已确认 / 采用新载荷」。

## 运行状态转移

```
                 create                首个确认样本
  (无) ───────────────────▶ CREATED ──────────────────▶ RUNNING
                              │  pause                    │  │  │
                              ▼                           │  │  └──────────────┐
                            PAUSED ◀──────── pause ◀──────┘  │                 │
                              │  resume                      │                 │
                              └──────────▶ RUNNING ◀─────────┘                 │
                                                            │                 │
        ┌───────────────────────────┬───────────────────────┼─────────────────┤
        ▼                           ▼                       ▼                 ▼
    CANCELLED                 DEVICE_FAULT              COMPLETED       重启时进程异常
   (终态·不可复活)           (终态·不可复活)           (终态·不可复活)    RUNNING→PAUSED
```

- `COMPLETED / CANCELLED / DEVICE_FAULT` 是**互不相同的终态**：拒绝继续 tick、
  拒绝恢复、拒绝再写入测点（API 返回 `REFUSED_TERMINAL`，测点不落库）。
- 只有 `PAUSED` 能恢复。
- **重启恢复语义**：进程启动时，所有处于 `RUNNING` 的行说明上次是非干净停止，
  统一落为 `PAUSED` 并标 `PAUSED` 中断原因，必须显式恢复；虚拟时钟、已确认测点、
  步骤窗口、LOOP 剩余次数和设备模拟器状态都在 SQLite 里，恢复后继续确定性推进。
- 周期中断原因独立区分：`NORMAL / PAUSED / CANCELLED / DEVICE_FAULT / CONFLICT_BLOCKED`，
  派生结果逐周期带出。

## 测点确认窗口（恰好一次）

- `(run_id, seq)` 是数据库唯一键；去重判断与物理写入在**同一个事务**里提交。
- 设备「上报成功、但确认应答丢失」后重发同一 `seq + 完全相同载荷`：
  返回 `DUPLICATE_IGNORED`，**不新增行、不移动协议状态**，因此重连不会少记或多记。
- 同一 `seq` 再次携带**不同载荷**：不覆盖已确认行，而是写一条 `sample_conflict`，
  运行进入 `CONFLICT` 阻塞状态；在研究员裁决前，后续新 seq 一律 `REFUSED_BLOCKED`。
  - `KEEP_EXISTING`：丢弃重复载荷。
  - `ACCEPT_NEW`：原始行保留作审计，冲突载荷存为 `point_amendment`，供后续派生使用。
- 页面「下次确认丢应答」按钮和 `POST /api/runs/{id}/inject/lost-ack` 用于注入该场景：
  设备在该点确认后立即认为链路断开、停止本批后续发送并缓存，重连后只重放未确认部分。

## 迟到样本与步骤发布

- 阈值（电压上限 / 下限、CV 电流截止）**只在新确认样本证明跨过之后**才转移；
  恰好等于阈值的样本算作跨过，转移样本同时是旧步骤的退出点和新步骤的进入点。
- 静置只在确认样本的时间达到持续时间后离开。
- `ts` 早于当前虚拟时钟（或 seq 倒退）的样本标记 `late=true` 并保留为原始证据，
  **不允许使已发布步骤倒退或重写步骤窗口**；它会让下一次派生得到
  `NEEDS_REVIEW`（带 `LATE_SAMPLE` 标记）的新版本。

## 单位约定

| 量 | 存储单位 | 说明 |
| --- | --- | --- |
| 电压 | mV（整数） | |
| 电流 | mA（整数） | 符号=方向号：规则 v1 约定 **+ 充电、− 放电、0 静置** |
| 温度 | 0.1 ℃（整数 `temperatureCd`） | 如 254 = 25.4 ℃；超出规则门限的区间不参与积分 |
| 时间 | ms（虚拟时钟，相对运行起点） | |
| 容量 | µAh（长整数） | 页面换算显示 mAh（/1000） |
| 库仑效率 | 基点 bp（整数） | 9805 = 98.05 %，= 放电量 / 充电量 |

## 积分口径

- 方法：**不等间隔梯形法**，全程整数运算，无浮点漂移：

  `dQ(µAh) = (i1 + i2) × dt(ms) / 7200`

  （推导：平均电流 mA × 小时 × 1000 = `(i1+i2)/2 × dt/3 600 000 × 1000`）。

- **端点归属**：积分在「已发布步骤窗口」内部进行，只取 `enterTs ≤ t ≤ exitTs` 的
  测点。跨步骤转移的梯形区间不会被拆开或重复——恰好在端点上的测点属于它所离开的
  窗口（参与该窗口最后一个梯形），离开该端点的区间已属于下一窗口。
- 周期边界取 LOOP 跳转时刻（= 下一周期充电进入时刻），放电后静置完整留在本周期，
  周期之间不丢区间、不重复区间。
- `REST / LOOP` 窗口不计电量；方向号或死区使电流归零的区间不计电量。
- 研究员「排除传感器故障区间」是区间级排除：任何端点落在排除区间内、或温度越界的
  梯形都不计，并计入该周期的 `excludedIntervalCount`。
- 研究员「修正周期分界」必须对齐到一个**已存在的原始测点时间戳**。

## 派生版本（永不就地改写旧结果）

- 每次派生都新增一行 `derived_version` 与一组新的 `cycle_result`；旧的当前版本转
  `SUPERSEDED` 但完整保留。可以指定任意历史规则版本重算（按原规则重算）。
- 派生输入（排除区间 id、分界修正、是否含迟到 / 修正点）与规则 JSON 都快照在版本行上。
- 复核标记：`LATE_SAMPLE / EXCLUDED_INTERVAL / AMENDED_POINT / BOUNDARY_OVERRIDE /
  DEVICE_FAULT`；无任何标记才是 `PUBLISHED`，否则 `NEEDS_REVIEW`。

## REST API 摘要

```text
POST /api/runs                                 创建运行（可用默认版本）
GET  /api/runs                                 运行列表
GET  /api/runs/{id}                            运行 + 步骤窗口 + 测点 + 冲突 + 派生
POST /api/runs/{id}/tick            {"tickMs":300000}
POST /api/runs/{id}/pause | /resume | /cancel
POST /api/runs/{id}/device-fault
POST /api/runs/{id}/link/offline | /link/online
POST /api/runs/{id}/inject/lost-ack
POST /api/runs/{id}/samples          直接上报测点（外部设备适配 / 测试）
POST /api/runs/conflicts/{cid}/resolve   {"resolution":"KEEP_EXISTING|ACCEPT_NEW"}
POST /api/runs/{id}/exclusions       {"fromTsMs":..,"toTsMs":..,"reason":".."}
POST /api/runs/{id}/boundaries       {"cycleNo":1,"boundaryTsMs":<原始点ts>}
POST /api/runs/{id}/derive           {"ruleVersionId":1,"reason":".."}
GET  /api/catalog
```

## 测试覆盖

`mvn -q test`（内存 SQLite）覆盖：

1. 确认窗口崩溃后同 seq 重发：恰好一个测点、seq 连续。
2. 重复序号不同载荷：进入冲突、不覆盖、阻塞新 seq、两种裁决路径。
3. 迟到样本：保留、不倒退已发布步骤、派生版本标 `NEEDS_REVIEW`。
4. 跨周期端点：转移样本恰落在端点、三个周期容量/效率一致、周期 2 与 3 逐位相同。
5. 按另一规则版本重算：新版本追加、旧版本 `SUPERSEDED` 且数值不变。
6. 排除传感器故障区间：只影响新派生、原始点不删除。
7. 四种终态/可恢复态区分，终态拒绝复活。
8. 掉线缓冲与重连重放：不丢不重；重启后 RUNNING 落 PAUSED 再恢复续跑；
   分界修正必须吸附原始测点。
