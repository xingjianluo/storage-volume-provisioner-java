# 本地存储卷供给与绑定控制流程（storage-volume-provisioner-java）

用本地内存存储模拟后端存储，实现一套具备确定性结果与可解释错误的存储卷
供给（Provision）、绑定（Bind/Claim）、回收（Reclaim）与快照恢复（Snapshot Restore）
控制流程。所有判定都有稳定的错误代码/原因代码，日志中打印输入、状态变化与判定依据。

## 1. 运行环境

- JDK 21+（在 JDK 25 上同样可构建）
- Maven 3.9+

## 2. 核心模型与状态机

### 2.1 卷（PersistentVolume）

| 字段 | 含义 |
| --- | --- |
| `capacityBytes` | 卷容量（字节） |
| `supportedModes` | 支持的访问模式：`ROX` / `RWO` / `RWX` |
| `storageClass` | 存储类；空值统一按 `default` 处理 |
| `reclaimPolicy` | 回收策略：`DELETE` / `RETAIN` / `RECYCLE` |
| `state` | `AVAILABLE` / `BOUND` / `RELEASED` / `FAILED` |

### 2.2 请求（VolumeRequest / Claim）阶段

`PENDING` → `BOUND` → `RELEASED`；另有 `UNSATISFIABLE` / `FAILED`。
PENDING 时 `reason` 字段给出**可区分**原因：

| reason 代码 | 含义 |
| --- | --- |
| `NO_VOLUMES` | 池中还没有任何卷 |
| `STORAGE_CLASS_NOT_MATCHED` | 没有同存储类的卷 |
| `INSUFFICIENT_CAPACITY` | 同存储类下没有容量足够的卷 |
| `ACCESS_MODE_NOT_SUPPORTED` | 容量够的卷不支持请求要求的访问模式 |
| `ALL_MATCHING_VOLUMES_BOUND` | 条件都满足的卷都已被占用（含并发抢绑定失败） |
| `INVALID_REQUEST` | 请求参数本身非法 |

### 2.3 合法状态流转

```
AVAILABLE ──bind──► BOUND
BOUND ──release(DELETE)──► 数据删除 + 卷记录移除（终态）
BOUND ──release(RETAIN)──► RELEASED（数据保留，等待人工处理）
BOUND ──release(RECYCLE)──► AVAILABLE（数据清空，可再次供给）
RELEASED ──reuse（人工确认）──► AVAILABLE
AVAILABLE / RELEASED / FAILED ──delete──► 记录移除
后端操作失败 ──► FAILED
```

**非法流转（如删除 BOUND 卷、重复释放、复用非 RELEASED 卷）一律返回
`ILLEGAL_TRANSITION`，绝不静默忽略，状态保持原样。**

## 3. 绑定规则

按以下顺序短路判定，全部满足才允许绑定：

1. 卷存在且为 `AVAILABLE`；
2. **容量**：`volume.capacityBytes >= request.capacityBytes`；
3. **访问模式**：请求声明的每一个模式都必须被卷支持（请求模式是必要条件集合）；
4. **存储类**：卷与请求的存储类相等（空值都归一化为 `default`）。

自动匹配（`POST /api/claims/{name}/bind`）在不满足时**不抛错**，请求保留在
`PENDING` 并写入上表中的原因代码；显式绑定（`.../bind/{volume}`）不满足时
返回稳定错误代码：`CAPACITY_NOT_MATCHED` / `ACCESS_MODE_NOT_MATCHED` /
`STORAGE_CLASS_NOT_MATCHED` / `VOLUME_ALREADY_BOUND`。

### 并发安全（不允许重复绑定）

- 每个卷名对应一把稳定监视器锁，"检查 AVAILABLE → 翻转为 BOUND" 在同一把锁内原子完成。
- 多卷扫描按卷名字典序统一加锁，防止交叉加锁死锁。
- 因此 N 个请求并发争抢同一块卷时，**恰好一个成功**（`BOUND`），
  其余得到 `VOLUME_ALREADY_BOUND`（显式）或
  `ALL_MATCHING_VOLUMES_BOUND`（自动），卷最终只绑定到唯一请求。

## 4. 回收语义（Reclaim）

释放（`POST /api/claims/{name}/release`）按**卷自身**的 reclaimPolicy 执行：

- **DELETE**：删除后端数据并从卷池移除记录；请求置 `RELEASED`。接口 `volumeResult=REMOVED`。
- **RETAIN**：仅解绑，卷进入 `RELEASED`，数据原样保留；需人工调用
  `POST /api/volumes/{name}/reuse` 才回到 `AVAILABLE`。
- **RECYCLE**：清空卷数据后回到 `AVAILABLE`，可被新请求立即再次绑定。

## 5. 快照与恢复（原子性）

- 创建快照：在后端锁内对当前数据做深拷贝存入快照区。提交前的任何失败都不会留下
  快照记录，也不会改动卷数据。
- 恢复快照（仅允许 `BOUND` 卷）采用 **暂存 → 提交** 两段式：
  1. 在暂存区构建新数据（此时旧数据仍在线上）；
  2. 提交阶段一次性引用切换，对外表现为"要么完整旧数据、要么完整新数据"。
- 提交前失败：卷数据保持恢复前内容、卷状态保持 `BOUND`，错误向上抛
  `STORAGE_BACKEND_FAILURE`，**不留下半新半旧的数据**；故障只触发一次，随后可重试成功。

## 6. 旧格式兼容与升级策略

`POST /api/legacy/upgrade`，请求体形如 `{"version":1,"fields":{...}}`。

- **v1**：`size` 为二进制计数字符串（`Ki/Mi/Gi/Ti` 或纯字节数），
  `mode` 为 `RO/RW/RWM`；无 `storageClass` 时确定性补 `default`，
  无 `reclaim` 时确定性补 `RETAIN`。
- **v2**：`sizeMb` 为非负整数；`modes` 为逗号分隔的
  `ReadOnlyMany/ReadWriteOnce/ReadWriteMany`；`storageClass`、`reclaim` 必填。

策略：

- **确定性、可复现**：升级只依赖输入字段，不读取时间/随机数/环境；多值字段顺序归一。
  同一条记录升级任意次结果完全一致。
- **错误可区分**：缺字段 → `LEGACY_MISSING_FIELD`；字段值非法（负容量、未知枚举、
  未知单位等）→ `LEGACY_INVALID_FIELD`。
- **高版本明确拒绝**：`version > 2` 返回 `UNSUPPORTED_RECORD_VERSION`
  （HTTP 426），**绝不按旧记录猜测处理**；`version < 1` 同样拒绝。

## 7. REST 入口一览

| 方法/路径 | 说明 |
| --- | --- |
| `POST /api/volumes` | 注册（供给）卷 |
| `GET /api/volumes` / `GET /api/volumes/{name}` | 列出/查看卷 |
| `DELETE /api/volumes/{name}` | 删除卷（BOUND 返回 409） |
| `POST /api/volumes/{name}/reuse` | Retain 卷人工复用 |
| `PUT` / `GET /api/volumes/{name}/data` | 写入/读取卷数据 |
| `POST /api/volumes/{name}/snapshots` | 创建快照 |
| `GET /api/volumes/{name}/snapshots` | 列出快照 |
| `POST /api/volumes/{name}/snapshots/{snap}/restore` | 原子恢复 |
| `POST /api/claims` | 创建请求并立即尝试绑定 |
| `GET /api/claims/{name}` | 查看阶段 + reason |
| `POST /api/claims/{name}/bind` | 自动匹配 |
| `POST /api/claims/{name}/bind/{volume}` | 显式绑定 |
| `POST /api/claims/{name}/release` | 按回收策略释放 |
| `POST /api/legacy/upgrade` | 旧记录升级 |

错误响应统一为 `{"code": "<稳定代码>", "message": "<可解释明细>"}`，
调用方应按 `code` 分支处理。主要 HTTP 映射：400（参数/旧字段问题）、404（不存在）、
409（已绑定/非法流转）、422（条件不匹配）、426（记录版本过高）、500（后端故障）。

## 8. 本地验证

```bash
# 编译并运行全部单测/集成测试（共 34 个）
mvn test

# 只跑某一类场景
mvn -Dtest=ConcurrentBindingTest test        # 并发重复绑定，唯一赢家
mvn -Dtest=UnsatisfiableRequestTest test     # 不可满足请求，原因可区分
mvn -Dtest=LifecycleTransitionTest test      # Delete/Retain/Recycle 与非法流转
mvn -Dtest=SnapshotRestoreTest test          # 快照恢复成功/失败整体回滚
mvn -Dtest=LegacyRecordUpgradeTest test      # v1/v2 升级、缺失/非法、高版本拒绝
mvn -Dtest=WebApiIntegrationTest test        # 全部 REST 入口的一致性
```

测试日志会打印**输入、状态变化（如 `state:BOUND->RELEASED`）与判定依据
（`basis=...`、`reason=...`）**，例如：

```
bind-won request=claim-7 volume=vol-1 phase:PENDING->BOUND state:AVAILABLE->BOUND basis=capacity=... modes... class=default
bind-race-lost request=claim-8 volume=vol-1 basis=volume-state=BOUND boundTo=claim-7
snapshot-restore-rollback volume=vol snapshot=snap state=BOUND dataUnchanged=true code=STORAGE_BACKEND_FAILURE
```

### 手工 curl 冒烟

```bash
mvn spring-boot:run            # server.port=0 时查看启动日志端口；可改为 -Dspring-boot.run.arguments=--server.port=8080

curl -s -XPOST localhost:8080/api/volumes -H 'Content-Type: application/json' \
  -d '{"name":"v1","capacityBytes":1073741824,"accessModes":["RWO"],"storageClass":"default","reclaimPolicy":"Retain"}'
curl -s -XPOST localhost:8080/api/claims -H 'Content-Type: application/json' \
  -d '{"name":"c1","capacityBytes":1073741824,"accessModes":["RWO"],"storageClass":"default"}'
curl -s localhost:8080/api/claims/c1
curl -s -XPOST localhost:8080/api/legacy/upgrade -H 'Content-Type: application/json' \
  -d '{"version":1,"fields":{"name":"old","size":"2Gi","mode":"RWM"}}'
```

## 9. 代码结构

```
model/      枚举（状态/策略/访问模式）、卷、请求、请求状态、快照
error/      稳定错误代码 ProvisionerErrorCode + ProvisionerException
compat/     旧记录表示 LegacyVolumeRecord 与确定性升级器 LegacyRecordUpgrader
storage/    LocalStorageBackend：内存数据块、快照深拷贝、两段式原子恢复、故障注入
service/    VolumeRegistry（并发同步点）、ProvisioningService、LifecycleService、
            SnapshotService、Spring 装配
web/        ProvisionerController（REST）、GlobalExceptionHandler（错误代码映射）、DTO
```
