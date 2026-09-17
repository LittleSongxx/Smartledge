# 真实验收走查问题清单

日期：2026-09-17  
环境：https://smartledge.cn（线上）  
账号：`admin` / `curator` / `alice`  
范围：会话端 + 管理端全页面走查，只读 API 矩阵；未改代码。  
约定：后面按优先级改。`P0` 先修，`P1` 下一轮，`P2` 体验。

事实等级：

- `CODE_CONFIRMED`：当前源码直接对上
- `DATA_CONFIRMED`：线上页面或接口直接看到
- `CONFIRMED`：源码 + 线上交叉确认

---

## 走查时仍然正常的部分

这些不要当回归误伤：

- 错误密码 `401 账号或密码不正确`；无 token 调会话列表 `401 请先登录`。`DATA_CONFIRMED`
- `alice` 不能登录管理台：`403 当前账号不是管理账号`。`DATA_CONFIRMED`
- 用户 token 调 `/manage/**` 被拒。`DATA_CONFIRMED`
- `alice` 读 curator 会话/轮次：`403 会话不存在或不属于当前账号`。`DATA_CONFIRMED`
- curator 勾选演示知识库后，**自动知识**能答对审计系统问题，并给出 `[1]` 与来源卡 `O6跨文档图谱-审计系统别名说明B.md`，约 21s。`DATA_CONFIRMED`
- 知识运行全景、参数配置、成功文档详情工作台可用。`DATA_CONFIRMED`
- curator 侧栏没有「用户与角色」；alice 会话端没有「管理后台」。`DATA_CONFIRMED`

---

## P0 必须先修

### 1. 开放提问直接失败：`checkpoint 缺少租户上下文`

- 现象：默认模式「开放提问」发问后整轮失败，原文回给用户。失败会话 `3e008371938741628d4deb5a15a71331`。
- 原因：`ChatCheckpointManager.stateKey()` 读 `TenantContext`；`ReactAgentExecutor` 在 `boundedElastic` 上 `states.begin()`，没有 `TenantContext.runWith(task.tenantId(), …)`。`RagChatExecutor` 的检索路径有包装，所以自动知识能过。
- 证据：`CODE_CONFIRMED` + `DATA_CONFIRMED`
- 改法：Agent 的 begin/save/finish/get/clear 全部包进 `task.tenantId()`；补 `ReactAgentExecutor` 租户传播测试。默认模式在修好前不要落在开放提问。

### 2. 成员页把所有人都显示成「停用」

- 现象：admin 看「用户与角色」，admin / curator / alice 三人都是停用，操作按钮却是「启用」。账号实际都能登录。
- 原因：Jackson 开了 `WRITE_NUMBERS_AS_STRINGS`，`status` 是 `"1"`；页面用 `member.status === 1`。
- 证据：`CONFIRMED`
- 改法：统一 `Number(member.status) === 1`，或在成员 VO 解包时转回数字。同类严格相等都要扫一遍。

---

## P1 功能/权限/演示闭环

### 3. 运营总览、路由追踪被会话必填打穿

- 现象：总览横幅「查询路由追踪必须指定会话」；路由健康度 0 条；路由追踪页同样报错，列表空。
- 原因：`KnowledgeManageServiceImpl.queryRouteTracePage` 空 `conversationId` 直接抛错；总览仍 `queryKnowledgeRouteTracePage({ pageNo: 1, pageSize: 200 })`。
- 证据：`CONFIRMED`
- 改法：管理端列表查询允许不带会话（仍按租户过滤）；或总览/追踪页先选会话再查。不要为了过页面把会话 ID 写死。

### 4. 对话观测走用户会话 API，管理端会被踢去用户登录

- 现象：观测列表用 `chatApi.listSessionsPage`（`/api/chat/session/list`），只看当前用户会话。admin 打开轮次详情时，用户 token 一 401，`handleUnauthorized` 整页跳到 `/login`，管理台从地址栏消失。
- 证据：`CODE_CONFIRMED` + `DATA_CONFIRMED`
- 改法：管理观测用 `/manage/**` 租户级会话列表；401 只清对应端登录态，不要把管理页赶到用户登录。

### 5. 普通用户 alice 在演示里无知识可用

- 现象：`/api/chat/document/options`、`knowledge-base/options` 都是空列表。设置区写「暂无可用知识库，可在管理端创建」，alice 进不了管理端。历史会话停在「没有可检索的已就绪文档」。切「当前文档」被拦：先选知识库。
- 证据：`DATA_CONFIRMED`
- 改法：给 USER 角色可读演示库/已索引文档；空态文案改成「请联系知识库管理员授权」，不要指向她进不去的后台。

### 6. 默认模式放大 P0

- 现象：新对话默认 `OPEN_CHAT`。根路径 `/` → `/chat` 也落到空白开放提问。
- 证据：`CODE_CONFIRMED` + `DATA_CONFIRMED`
- 改法：修好开放提问后，默认改为自动知识（有库时预勾演示库）；无库时明确空态，不要默默走 Agent。

### 7. 16 篇文档几乎不能检索

- 水位：解析成功 16；策略已确认 2；索引成功 1；索引失败 1（`生产环境发布与回滚操作规范.md`）；其余 14 篇停在「已推荐 / 待构建」。
- 失败横幅仍是旧 RAPTOR `503` + `缺少 sentence-transformers`（云 embedding 修好之前的任务）。
- 证据：`DATA_CONFIRMED`
- 改法：失败文案收成阶段+原因，不要甩 pip；确认后的文档提供「一键构建」；演示至少再备几篇已索引文档。

### 8. 文档授权页按钮和真实权限不一致

- 现象：curator 能进授权页、16 篇都有「授权」；点已索引文档得到「当前账号没有该文档的授权管理权限」。
- 证据：`DATA_CONFIRMED`
- 改法：不能管理的文档不要放授权按钮，或只读展示；明确 owner / `document:acl:manage` 的边界。

### 9. 路由守卫只藏菜单，深链仍可进无权限页

- 现象：curator 打开 `/admin/members`，没有菜单项，但页上仍有「新建成员」，正文 403。
- 证据：`DATA_CONFIRMED`
- 改法：路由按权限拦截；无权限不要渲染写操作。

### 10. 问答成功后标题仍是「新的对话」

- 现象：侧栏已有问题摘要，主标题还是「新的对话」。
- 证据：`DATA_CONFIRMED`
- 改法：首轮用户问题一经确认就写标题。

---

## P2 体验与品牌

| ID | 问题 | 证据 |
| :--- | :--- | :--- |
| 11 | 品牌标是 `NA`；路由切换闪「超级智能」（`App.vue` 非 fullscreen 壳） | `CONFIRMED` |
| 12 | 侧栏角色写死「管理员」，curator 也被这么叫 | `CODE_CONFIRMED` |
| 13 | 几乎每页标题重复两次（PageHeader + 布局） | `DATA_CONFIRMED` |
| 14 | 未勾知识库时切自动知识/当前文档：必须先选库，默认又不勾 | `CODE_CONFIRMED` |
| 15 | 观测统计「本页文档问答」只数 `DOCUMENT`，自动知识算 0 | `CODE_CONFIRMED` |
| 16 | 观测/授权列表露出长会话 ID、文档雪花 ID | `DATA_CONFIRMED` |
| 17 | 索引失败把 Python JSON / pip 说明直接给运营 | `DATA_CONFIRMED` |
| 18 | 授权弹窗混用 `MANAGE ⇒ WRITE ⇒ READ` | `DATA_CONFIRMED` |
| 19 | 知识库更新时间为 `-`；文档「未配置可展示的属性」空态弱 | `DATA_CONFIRMED` |
| 20 | 知识路由范围/主题/关联全 0，覆盖率 0%，演示没预置 | `DATA_CONFIRMED` |
| 21 | 「如何学习」指向知识星球付费文，仓库内无公开架构页 | `CODE_CONFIRMED` |
| 22 | 设置折叠时模式按钮仍在无障碍树里，容易点到被输入条挡住 | `DATA_CONFIRMED` |
| 23 | 图谱/表格通道启用但本轮召回 0（文档未建 Graph/RAPTOR，易误解） | `DATA_CONFIRMED` |

---

## 建议下一轮改的顺序

1. `ReactAgentExecutor` 租户上下文 + 开放提问回归。
2. 成员状态字符串比较，顺手扫 `=== 1` / `=== 0`。
3. 路由追踪列表与总览合同（会话可选 vs 必填）。
4. 管理观测改 manage API，禁止 401 把管理台踢去 `/login`。
5. 演示 ACL：alice 能读已索引文档；空态文案。
6. 默认模式与首轮标题。
7. 品牌 `NA` / 「超级智能」闪屏。

未做（避免和现网构建抢手）：没有重跑 15 篇索引，没有改成员/ACL，没有改 README。
