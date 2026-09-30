# Storage Volume Provisioner（本地存储模拟）

用本地内存存储模拟后端存储设备，实现一套卷（PV）与卷请求（PVC）的
**供给、绑定、回收、快照恢复、旧格式升级**控制流程。目标是让同一段语义
在“创建时自动匹配 / 显式绑定 / 后台调和 / HTTP 入口”下结果一致、可解释。

- 技术栈：Java 21（编译目标）、Spring Boot 3.4、内存存储（`byte[]` 模拟块设备）
- 存储实现：`storage/LocalStorageBackend`，每块卷对应固定容量字节数组；越界即拒绝
- 持久化：进程内内存注册表（`VolumeStore` / `ClaimStore` / `SnapshotStore`），重启清空

---

## 1. 核心模型

| 模型 | 状态 | 说明 |
| --- | --- | --- |
| PersistentVolume（PV） | `AVAILABLE / BOUND / RELEASED / FAILED` | 卷，含容量、访问模式、存储类、回收策略 |
| PersistentVolumeClaim（PVC） | `PENDING / BOUND / LOST` | 卷请求，记录 `pendingReason` 解释为何待处理 |
| Snapshot | 不可变 | 保存某时刻整盘字节内容 |

合法 PV 状态流转（其它一律以 `ILLEGAL_STATE_TRANSITION` 拒绝，绝不静默忽略）：

```
AVAILABLE -> BOUND        被某个 PVC 绑定
BOUND     -> RELEASED   绑定的 PVC 被删除
RELEASED  -> AVAILABLE   Retain 人工放行 / Recycle 清空完成
RELEASED  -> FAILED      回收过程存储层失败
FAILED     -> RELEASED    人工复位后重试
```

---

## 2. 绑定规则

匹配条件（必须**全部**满足才算匹配）：

1. 卷处于 `AVAILABLE`；
2. 存储类完全一致（`null` 只与 `null` 匹配，视为“不指定存储类”）；
3. `卷容量 >= 请求容量`（容量不足不允许超配）；
4. 卷支持**全部**请求要求的访问模式（RWO / ROX / RWX）。

不可满足时请求**留在 `PENDING`**，HTTP 返回 `200 + success=false`，
并按固定优先级给出唯一一个可区分原因（候选卷列表按名称排序，结果确定可复现）：

| 优先级 | reason | 含义 |
| --- | --- | --- |
| 0 | `STORAGE_CLASS_NOT_MATCHED` | 存储类不匹配（最高优先级） |
| 1 | `CAPACITY_NOT_SATISFIED` | 容量不足 |
| 2 | `ACCESS_MODE_NOT_SATISFIED` | 访问模式不全满足 |
| - | `NO_VOLUME_AVAILABLE` | 没有任何 AVAILABLE 卷 |
| - | `CLAIM_ALREADY_BOUND` / `VOLUME_ALREADY_BOUND` | 重复绑定 |

### 并发安全（同一块卷绝不重复绑定）

- 绑定是“在卷自身锁内”的 check-then-set：`PersistentVolume#tryBind` 是
  `synchronized` 原子操作，只有 `AVAILABLE` 才能预留成功；
- 预留卷后再在 claim 锁内确认仍为 `PENDING`；若 claim 已在并发中被别人绑定，
  则用 `rollbackBind` 把卷补偿退回 `AVAILABLE`，不产生悬挂/重复绑定；
- 测试 `ConcurrentBindingTest` 以 32 并发 × 16 线程验证：始终恰好 1 个 claim 绑定。

---

## 3. 回收与生命周期规则

删除一个 BOUND 请求时，先把卷置为 `RELEASED` 并解除引用，再按卷的回收策略处理：

| ReclaimPolicy | 行为 | 终态 |
| --- | --- | --- |
| `DELETE` | 删除存储设备、删除卷、级联删除其快照 | 卷不存在 |
| `RETAIN`（旧记录缺省） | 保留卷与数据，等待人工处理 | `RELEASED` |
| `RECYCLE` | 整盘清零后重新可绑定 | `AVAILABLE` |

补充规则：

- `PENDING` 请求可直接删除，不影响任何卷；`LOST` 请求可删除清理；
- BOUND 卷不允许管理面直接删除（`VOLUME_IN_USE`），必须先删绑定它的请求；
- `RELEASED`（Retain）与 `FAILED` 卷可由管理面人工删除；
- 回收策略仅允许在 `AVAILABLE` 时修改（`RECLAIM_POLICY_CHANGE_REJECTED`）；
- 回收过程存储层失败 -> 卷转 `FAILED`（`RECLAIM_FAILED`），修正后可 `FAILED -> RELEASED` 重试。

---

## 4. 快照与恢复的原子性

- 快照 = 某时刻整盘字节的防御性拷贝；快照记录不可变。
- 恢复前置校验：快照必须属于目标卷（`SNAPSHOT_VOLUME_MISMATCH`）、
  快照容量必须等于卷当前容量（`SNAPSHOT_RESTORE_CAPACITY_MISMATCH`）。
- **原子恢复协议**：恢复前先完整备份当前盘，再按 64 字节块逐块写入；
  任一块写入失败，立即用备份整体回滚 —— 要么整盘恢复成功，要么盘内容
  与恢复前**完全一致**，不会留下半新半旧数据（`SNAPSHOT_RESTORE_FAILED`）。
  回滚成功后故障清除即可重试。
- `LocalStorageBackend#setFailureHook` 可在任意写入块前注入故障，专用于验证回滚。

---

## 5. 旧格式兼容与升级策略

迁移器：`legacy/LegacyVolumeMigrator`（纯函数，无副作用，相同输入恒定输出）。

旧记录（唯一支持 `formatVersion=1`）：

```json
{ "formatVersion": 1, "id": "vol-old-1", "sizeGb": 10,
  "mode": "RW", "reclaim": "delete", "diskClass": "ssd" }
```

| 旧字段 | 规则 | 升级结果 |
| --- | --- | --- |
| `formatVersion` | 缺失/非整数 → 拒绝 | 仅接受 `1` |
| `id` | 必填非空字符串 | PV 名称 |
| `sizeGb` | 必填正整数（GB，1 GB = 1024³ 字节） | `capacityBytes = sizeGb * 1024^3` |
| `mode` | 可选，默认 `RW`；仅认 `RO/RW` | `RO -> {RWO,ROX}`，`RW -> {RWO,RWX}` |
| `reclaim` | 可选，默认 `retain` | DELETE/RETAIN/RECYCLE |
| `diskClass` | 可选，缺失/空视为不指定 | `storageClassName`（null 表示不指定） |

拒绝原因可区分：

- 版本高于 1（或缺失、更低）→ `UNSUPPORTED_RECORD_VERSION`（HTTP 422），
  **绝不按旧记录静默处理**，避免把新格式误判成旧数据；
- 必填字段缺失 → `LEGACY_FIELD_MISSING`（HTTP 400）；
- 字段值非法（sizeGb<=0/非整数、mode 不认识、reclaim 不认识、JSON 非法等）
  → `LEGACY_FIELD_INVALID`（HTTP 400）。

升级确定性：枚举集合规整、容量固定换算、字段缺省值固定，因此同一记录多次升级
得到的容量/模式/策略/存储类/初始状态完全一致（`LegacyMigrationTest` 双跑比对）。

---

## 6. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/volumes` | 供给卷（建设备） |
| GET | `/api/volumes`、`/api/volumes/{name}` | 查询卷 |
| POST | `/api/volumes/{name}/data` | 写数据（base64，body：offset/base64Data） |
| GET | `/api/volumes/{name}/data?offset&length` | 读数据（base64） |
| PUT | `/api/claims` | 创建请求并立即尝试一次绑定 |
| POST | `/api/claims/{name}/bind` | 显式触发绑定（成功 BOUND / 失败 PENDING） |
| POST | `/api/claims/reconcile` | 后台语义：尝试绑定所有 PENDING 请求 |
| GET | `/api/claims`、`/api/claims/{name}` | 查询请求（含 pendingReason） |
| DELETE | `/api/claims/{name}` | 删除请求并触发回收策略 |
| POST | `/api/snapshots/volumes/{volumeName}` | 创建快照（body：name） |
| POST | `/api/snapshots/{name}/restore` | 原子恢复（body：volumeName） |
| GET | `/api/snapshots` | 列出快照 |
| DELETE | `/api/admin/volumes/{name}` | 管理面删除卷（BOUND 拒绝） |
| POST | `/api/admin/volumes/{name}/recycle-available` | RELEASED 人工放行到 AVAILABLE |
| POST | `/api/admin/volumes/{name}/reclaim-policy` | AVAILABLE 时改回收策略 |
| POST | `/api/admin/volumes/legacy/import` | 导入并升级旧格式记录 |

错误响应统一结构：

```json
{ "success": false, "action": "ERROR", "reason": "CAPACITY_NOT_SATISFIED", "message": "……" }
```

---

## 7. 本地验证方法

```bash
# 编译
mvn -q compile

# 全部单测（29 个，含并发/不可满足/非法流转/快照回滚/旧格式/HTTP）
mvn test

# 只跑某一组重点测试
mvn test -Dtest=ConcurrentBindingTest       # 32 并发重复绑定，只允许 1 个成功
mvn test -Dtest=UnsatisfiableRequestTest   # 容量/模式/存储类原因可区分
mvn test -Dtest=LifecycleReclaimTest      # DELETE/RETAIN/RECYCLE 与非法流转
mvn test -Dtest=SnapshotRestoreTest        # 中途失败整体回滚，无半新半旧
mvn test -Dtest=LegacyMigrationTest       # 确定性升级 + 缺字段/非法/高版本拒绝
mvn test -Dtest=WebApiIntegrationTest    # HTTP 入口一致性

# 启动后手工验证（application.properties 中 server.port=0 为随机端口，
# 如需固定端口可追加 --server.port=8080）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

手工冒烟（示例）：

```bash
# 供给卷
curl -s -X PUT localhost:8080/api/volumes -H 'Content-Type: application/json' \
  -d '{"name":"v1","capacityBytes":4096,"accessModes":["RWO"],"storageClassName":"ssd","reclaimPolicy":"RETAIN"}'

# 创建请求（容量不足 -> PENDING + CAPACITY_NOT_SATISFIED）
curl -s -X PUT localhost:8080/api/claims -H 'Content-Type: application/json' \
  -d '{"name":"c1","capacityBytes":8192,"accessModes":["RWO"],"storageClassName":"ssd"}'

# 导入旧格式 v1 记录
curl -s -X POST localhost:8080/api/admin/volumes/legacy/import -H 'Content-Type: application/json' \
  -d '{"recordJson":"{\"formatVersion\":1,\"id\":\"old-1\",\"sizeGb\":1}"}'
```

测试日志统一打印 `[输入] / [判定依据] / [状态变化] / [断言]`，
业务侧通过 SLF4J 打印绑定评估、每块候选卷的不匹配原因与状态流转（含 volume-state 标记）。
