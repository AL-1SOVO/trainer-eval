# 企业级 RAG 实施进度（MimirQ 集成）— 截至 2026-09-14

> 本文档是实施过程的进度快照 + 踩坑记录。完成后与《企业级RAG实施方案.md》合并。

## 一、本次会话已完成

### 1. 环境侦察（结论全部实测）
- Docker Desktop 已安装且守护进程已启动（29.4.3）——之前是没启动状态，本次用 PowerShell 拉起了 Docker Desktop
- 机器资源：31.7GB 内存 / 12 逻辑核，够跑 lite 栈
- Python 3.13 可用；`make` 没有安装（不影响，改手动执行等效 docker compose 命令）
- **网络关键结论**：api.deepseek.com 恢复可达（HTTP 401 = 网络通、认证通，之前两天是被校园网出口拦截）；github.com curl 可达
- ⚠️ 坑1：git 全局配置了 `http.proxy=127.0.0.1:7897` 但代理软件没运行，git clone 全部失败。
  **未改全局配置**，克隆时用 `-c http.proxy= -c https.proxy=` 单次置空绕过

### 2. MimirQ 已克隆到 `D:\mimirq-test`（depth=1，3623 文件）

### 3. MimirQ 代码调研结论（部署所需的关键事实）
- `make init` 只是跑 `scripts/init_env.py`（创建 .env，不覆盖已有文件）；`make up-lite` 等效命令为：
  `docker compose --project-name mimirq --env-file .env -f docker/docker-compose.lite.yml up -d --build`
- **lite 栈组成**（docker/docker-compose.lite.yml）：redis:7 + postgres:15 + mimirq-api(源码构建) + mimirq-worker(arq)。
  lite 用 Chroma 向量库（VECTOR_BACKEND=chroma）、无 Milvus/无 MinIO，单机 profile
- **Dockerfile 已内置清华 PyPI 镜像**（PIP_INDEX_URL=mirrors.tuna.tsinghua.edu.cn），构建下载无忧
- 镜像未拉取、容器未启动、**.env 尚未创建**——这是当前断点

### 4. 部署配置决策（写入 .env 用的）
- LLM：`LLM_API_BASE=https://api.deepseek.com/v1`，`LLM_MODEL=deepseek-chat`，key 用现有 DEEPSEEK_API_KEY
- Embedding（DeepSeek 无 embedding API，三选一）：
  - 首选 `EMBEDDING_PROVIDER=local` + `EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5`（~95MB，
    首次从 HF 下载，国内需 `HF_ENDPOINT=https://hf-mirror.com` 写进 .env 即可，huggingface_hub 自动识别）
  - 备选（纯跑通管道）：`deterministic_test` provider（哈希向量，检索质量无意义，仅验证链路）
  - 备选：SiliconFlow bge-m3（需注册 key，本次没有）
- ADMIN：`INITIAL_ADMIN_USERNAME/PASSWORD/EMAIL` 必须在 .env 设置（首次启动创建管理员）
- **Dify 外部知识接口**（Java 侧要调的端点）：
  - 开关 `DIFY_EXTERNAL_KNOWLEDGE_ENABLED=true`
  - `DIFY_EXTERNAL_KNOWLEDGE_API_KEYS=<自定义key>`（Java 侧 Bearer 认证用）
  - `DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON={"course-1":{"dataset_ids":["<建库后拿到的UUID>"]}}`
  - endpoint：`POST /api/v1/integrations/dify/retrieval`，请求体 `{knowledge_id, query, retrieval_setting:{top_k,score_threshold}}`
  - ⚠️ 坑2：dataset_ids 必须是 UUID，来源是登录 MimirQ 后建 dataset 时返回的 id；建库后要把 UUID 回填 .env 并重启 api 容器
- 其他默认不动：`DEFAULT_PARSER_BACKEND=deepdoc`、`ENABLE_RERANKER=false`（lite 先不开重排）

### 5. trainer-eval 侧基线（本次会话前已完成）
- Spring Boot 3.4.5 + Spring AI 1.0.0，学习辅导 Agent（AgentChatService 手动工具循环 + SSE + Redis 记忆）
- 现有 `TutorTools.searchCourseMaterials` 是 SQL LIKE（待被 RagSearchTool 替换）
- DeepSeek 修好网络后 Agent 已可用（此前 9/12 测过端到端）

## 二、下一步（按序执行）

1. **写 `D:\mimirq-test\.env`**（复制 .env.example 后改上述决策项；INITIAL_ADMIN_*、HF_ENDPOINT 别漏）
2. **启动 lite 栈**（首次构建 10~20 分钟，pip 走清华源）：
   `docker compose --project-name mimirq --env-file .env -f docker/docker-compose.lite.yml up -d --build`
   - ⚠️ 风险：redis:7/postgres:15 来自 Docker Hub，国内可能拉不动；失败则需配 registry mirror（docker.1ms.run 等，需改 Docker Desktop 设置）
   - 健康检查：`curl http://localhost:8000/api/v1/health/ready`（首次建 schema 需要 4 分钟，start_period=240s）
3. **建 dataset + 传文档**：用 admin 登录 `http://localhost:3000`（lite 无前端容器！需走 API 或把 web compose 叠加：
   `-f docker/docker-compose.yml -f docker/docker-compose.web.yml`）→ 建 dataset 记 UUID → 上传/粘贴课程资料文本
   - ⚠️ 坑3：lite 栈不含 web 前端，要么纯 API 操作，要么补叠 web compose（会多一个容器+pnpm build）
4. **回填 DIFY map** .env → 重启 mimirq-api → curl 验证 retrieval 返回 records
5. **Java 侧**：新建 `RagSearchTool`（RestClient 调 retrieval，5s 超时，按 tra_enrollment 课程路由，失败降级 SQL LIKE），
   替换 AgentChatService 中 `searchCourseMaterials` 的实现；SSE 增加 `citation` 事件；AiTutor.vue 渲染"参考来源"
6. **Golden 验证**：5~10 条"问题→应命中章节"对照表，记录命中情况
7. **写踩坑文档**（合并进本文件）+ 更新《企业级RAG实施方案.md》勾选

## 三、已知坑（持续补充）
- 坑1：git 全局代理指向未运行的本地代理（127.0.0.1:7897），所有 git 网络操作需单次置空
- 坑2：Dify map 的 dataset_ids 是 MimirQ 内部 UUID，建库后才能拿到
- 坑3：lite compose 不含前端容器（WEB_PORT 配置项在完整栈才生效）
- 坑4：后端 `spring-boot:run` 杀 Maven 父进程后 Java 子进程仍占 8080，重启前必须 `taskkill` netstat 找到的 PID
- （网络）Git Bash 是 GBK：中文 curl 测试要用 UTF-8 文件体 `--data-binary @file`

---

## 四、本轮完成：Java 侧接入（M3 / M4 / M5）— 2026-09-15

### 4.0 前置结论：MimirQ 尚未可服务，因此本轮只做 Java 侧

实测当前状态：`docker images` 无 mimirq 相关镜像（**从未构建过**）；`D:\mimirq-test\.env` 已存在但
`EMBEDDING_API_BASE=` 为空、`DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON={}`、8000 端口未监听。
即阶段一~三（部署 + 入库 + 建库回填）尚未完成，**MimirQ 侧不可达**。

所以本轮把方案文档里**属于本项目的部分（阶段三/四/五）全部实现并验证**，
MimirQ 真机联调留到它部署之后——而这条路径已经被设计成"服务不可用时自动降级"，不阻塞任何现有功能。

### 4.1 交付物

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| MimirQ 客户端 | `rag/mimirq/MimirQClient.java` | Dify External Knowledge 协议；独立连接/读超时；Bearer 鉴权 |
| 接入配置 | `rag/mimirq/MimirQProperties.java` | 对应方案文档 3.4 的 `mimirq.*`，另加熔断参数 |
| 检索门面 | `rag/service/RagRetrievalFacade.java` | 三级链路 + 熔断 + 统一轨迹 |
| 权限裁剪 | `rag/service/KnowledgeAccessService.java` | `tra_enrollment` → 可检索课程集合 |
| Golden 题集 | `resources/rag/golden-rag.jsonl` | **20 条**，含 11 条刻意无字面重叠的改写题 |
| 评测对比 | `rag/service/GoldenEvalService.java` | 现行链路 vs LIKE 基线，双策略分别留档 |
| 引用透传 | `AgentChatService` + `TutorTools` | 新增 SSE `citation` 事件 + 引用累积 |
| 前端渲染 | `AiTutor.vue` | 「参考来源」卡片，点击跳转课程学习页 |
| 新接口 | `RagAdminController` | `/rag/health`、`/rag/golden/import` |

检索链路（三者对上层是同一接口，Agent 与前端零改动）：

```
MimirQ(混合召回+重排) → 本地混合(ngram全文 + 子串 + RRF) → 子串兜底
```

### 4.2 实测结果

**MimirQ 不可用时的降级（这是当前真实状态）**

| 验证项 | 结果 |
| --- | --- |
| `/rag/search` 返回 | `backend=LOCAL_HYBRID`、`degraded=true`、`note=知识库服务不可用（…已降级为本地检索）` |
| 降级后检索质量 | 仍正确命中（`M4#0`，通道 `FT+LIKE`） |
| 降级耗时 | 首次 92ms（连接被拒），之后 15ms —— localhost 拒连是毫秒级 |
| 熔断 | 连打 4 次后 `circuitOpen=true`，冷却 59904ms，`lastFailure` 已记录 |
| 轨迹 | `rag_retrieval_trace.strategy = LOCAL_HYBRID`（记录**实际服务方**，非固定值） |

**Golden 评测：现行链路 vs 改造前 LIKE 基线（20 题，topK=5）**

| 策略 | HitRate@5 | Recall@5 | MRR | 未命中 |
| --- | --- | --- | --- | --- |
| CHAIN（现行链路） | **1.0000** | **1.0000** | **1.0000** | 0 |
| LIKE_BASELINE（改造前） | 0.4000 | 0.4000 | 0.4000 | 12 |
| 提升 | **+0.60** | **+0.60** | **+0.60** | |

> 之前用 7 条改写不足的题测出 7/7，两份资料下几乎无区分度，指标没有意义。
> 本轮刻意补到 20 条、其中 11 条与原文**无字面词重叠**（如"为什么要限制个人信息采集范围"对应"必要性原则"），
> 纯关键词检索的短板才被暴露出来 —— **基线选得不对，优化幅度就是自说自话。**

**权限裁剪（M4）**

| 主体 | 结果 |
| --- | --- |
| `admin`（含 MANAGER） | 不受限，正常检索到资料 |
| 纯 `ROLE_EMPLOYEE` 探针（无报名记录） | **hits=0 + 提示"未报名相关课程"** |

关键点：探针走的是**降级后的本地检索**路径，依然被裁剪 —— 证明裁剪没有 fail-open。

### 4.3 为什么不在 Java 里自己再写一套 RAG

方案文档第十节已经给了答案，本轮实践后可以补两条更具体的：

1. **工程质量在流水线的每一步**：MimirQ 是 30 解析器 + 86 切块策略 + 13 重排器；
   自研的"能跑"版本我在上一轮已经写过（`rag/service/RetrievalService`），
   它能解决"有/没有"和"字面命中"，但解决不了扫描件解析、表格还原、交叉编码器重排。
2. **职责边界才是有价值的架构决策**：Java 负责 Agent 编排与**业务权限**，
   知识流水线交给专业组件。所以本轮做的是"接入 + 裁剪 + 兜底 + 评测"，
   而不是在业务系统里堆解析器和重排器。

同时必须承认上一轮那套本地检索**不是白写的**：它就是现在的一级降级路径，
让"MimirQ 挂了"从"功能不可用"变成"检索质量下降但业务继续"。

### 4.4 本轮新踩的坑

- **坑5：调试接口绕过了门面。** `/rag/search` 原先直接调 `RetrievalService.debug()`，
  于是它**不经过 MimirQ、不经过熔断、不记录服务方** ——
  "调试通过"并不代表线上没问题，两套路径已经分叉。已改为走门面。
- **坑6：评测接口也绕过了门面。** `GoldenEvalService` 的 "CHAIN" 原先只调本地检索，
  **标签名不副实**：指标好看，但测的不是生产走的那条路。
  评测必须测"生产实际执行的链路"，否则数字毫无意义。已改为走门面。
  （这两个坑是同一类：**新增了一层抽象之后，忘了把所有入口都切过去**。）
- **坑7：降级必须与权限裁剪绑定，否则 fail-open。** 如果只在 MimirQ 路径做 security trimming，
  那么"知识库打挂 → 降级到不过滤的本地检索"就绕过了权限本身。
  这是降级设计里最危险的一类错误，且很容易在"只测正常路径"时漏掉。
- **坑8：MimirQ 响应里 `title` 是顶层字段，不在 `metadata` 里。**
  方案文档 3.1 的示例写成 `metadata: {title, chapter}`，与源码
  `DifyExternalKnowledgeRecord(content, score, title, metadata)` 不一致。
  **以源码为准**（我按源码实现的），metadata 只作自定义元数据容器。
- **坑9：Agent 场景应该用 `latency_profile: "fast"`。** 这是 MimirQ 的扩展字段
  （源码注释：keep online chat latency low by disabling expensive fallback/rerank branches），
  方案文档没提。工具调用是在线对话路径，默认已设为 `fast`。
- **坑10：Dify 协议一次只能查一个 `knowledge_id`。** 学生报名多门课时要发 N 次请求，
  已加 `max-courses-per-query: 5` 限制扇出（超出只查前 5 门）。
- **坑11：`enabled` 默认给 `true` 是安全的。** 一开始担心"MimirQ 没起会每次白等 5 秒"，
  所以把 `connect-timeout` 单独设为 1500ms —— localhost 拒连是**毫秒级**返回，
  实测首次 92ms、后续 15ms。配合熔断，默认开启不会拖慢任何提问。

### 4.5 下一步（回到 MimirQ 侧）

1. 定 embedding 供应商并填 `.env` 的 `EMBEDDING_API_BASE/KEY/MODEL`（**当前为空，这是硬阻塞**；
   换模型后必须重建索引，所以要先定稿）
2. `docker compose --project-name mimirq --env-file .env -f docker/docker-compose.lite.yml up -d --build`
3. 建 dataset `course-{courseId}` → 拿到 UUID → 回填 `DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON` → 重启 api
4. Java 侧**无需改代码**：`mimirq.enabled` 已是 `true`，服务一上线自动切到一级链路
5. 重跑 `POST /api/v1/rag/eval?topK=5`，此时 "CHAIN" 行的数字就是 MimirQ 的真实成绩，
   与 `LIKE_BASELINE`、以及本轮记录的"本地混合"三方对比
