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

---

## 五、MimirQ 实际部署记录 — 2026-09-16

### 5.1 现在的状态：**全链路已打通** ✅

| 环节 | 状态 |
| --- | --- |
| Docker Desktop | ✅ 已启动（Server 29.4.3） |
| 基础镜像 | ✅ `redis:7` / `postgres:15` 拉取成功（**文档标记的最大风险已排除**） |
| 镜像构建 | ✅ 成功（首次在 HF 模型下载处失败，重试即通过） |
| 容器 | ✅ api / worker / postgres / redis **全部 healthy** |
| dataset | ✅ `course-1` = `bc4eeab3-ce4c-4871-84e7-5a5cce043238` |
| 文档入库 | ✅ 2 份（M3、M4），各 1 chunk，`status=completed` |
| Dify 映射 | ✅ 已回填 `DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON` 并重启 api |
| **检索端点** | ✅ `POST /integrations/dify/retrieval` → **200，返回 records** |
| **Java 侧接入** | ✅ `/rag/search` 的 `backend=MIMIRQ`、`degraded=false`，引用为可点击的 `M4#0` |

**403 的根因与修法**：Dify 端点用的是**合成账号** —— 见 `external_api.py:1045`：
`account_id = DIFY_EXTERNAL_KNOWLEDGE_ACCOUNT_ID or "system:dify"`，
而 `system:dify` 不在 `tenant_members` 表里 → `Not a tenant member`。
**修法（配置驱动，不动数据库）**：把 `DIFY_EXTERNAL_KNOWLEDGE_ACCOUNT_ID` 设为 admin 的真实 user_id
（`b5060600-7626-41d4-a4dc-a7675d1c4648`），复用其已有的 owner 成员关系。
更"生产干净"的替代方案：往 `tenant_members` 插一条 `system:dify` 的成员记录，让审计归属独立。

**实测的端到端结果**：

```
/rag/search  钓鱼邮件怎么防范   -> backend=MIMIRQ  M4#0  materialId=4  score=0.15
/rag/search  数据保护三大原则   -> backend=MIMIRQ  M3#0  materialId=3  score=0.21
/rag/search  勒索软件怎么防范   -> backend=MIMIRQ  M4#0  materialId=4  score=0.13

Golden 评测（20 题 topK=5）
  CHAIN（走 MimirQ）  hitRate=1.0000  recall=1.0000  mrr=1.0000  miss=0
  LIKE_BASELINE       hitRate=0.4500  recall=0.4500  mrr=0.4500
  提升                +0.55
```

### 5.1.1 Java 侧接入过程中修掉的两个真实缺陷

1. **`resolveTargetCourses` 漏了内容过滤**：注释写着"反推有内容的课程"，但查询没加
   `content is not null` 条件 → 对只有占位资料（"复习"、"video" 这类空正文）的课程也发请求，
   MimirQ 返回 `2001 Dify knowledge mapping not found`。
   修复：加 `.isNotNull(content).ne(content, "")`。
2. **缺少"部分可用"容错**：原先**任一课程失败就让整次检索降级** —— 一门课没建知识库就打挂了整条链路。
   修复：逐课程捕获异常并跳过，**只有所有课程都失败**才抛错交给上层降级/熔断；
   部分失败记日志并返回已取到的结果。这是企业级集成必须有的容错姿态。

**Java 侧无需改代码**：`mimirq.enabled` 已是 `true`；端点一旦通，
`/rag/health` 的 `circuitOpen` 会转 false、`/rag/search` 的 `backend` 会变成 `MIMIRQ`。

### 5.2 用到的部署配置（`D:\mimirq-test\.env`）

```
SECRET_KEY=<随机 48 位>                      # 必填，原 .env 缺失
LLM_API_BASE=https://api.deepseek.com/v1     # 复用现有 key
LLM_MODEL=deepseek-chat
LLM_API_KEY=sk-****
EMBEDDING_PROVIDER=deterministic_test        # 先跑通链路：无 key、无模型下载
EMBEDDING_MODEL=mimirq-deterministic-test-v1
EMBEDDING_DIM=256
DIFY_EXTERNAL_KNOWLEDGE_ENABLED=true
DIFY_EXTERNAL_KNOWLEDGE_API_KEYS=<随机>      # 启用时必须非空，否则启动校验失败
DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON={"course-1":{"dataset_ids":["bc4eeab3-..."]}}
INITIAL_ADMIN_USERNAME=admin
INITIAL_ADMIN_EMAIL=admin@mimirq.com         # 原来缺失 → 容器启动崩溃
INITIAL_ADMIN_PASSWORD=****
```

### 5.3 可复现的操作序列

```bash
# 1) 启动 Docker Desktop，轮询等守护进程就绪（不要立刻构建）
# 2) 先验证 Docker Hub 可达
docker pull redis:7 && docker pull postgres:15
# 3) 构建 + 启动（首次约 15 分钟；HF 模型下载失败就重试一次）
cd /d D:\mimirq-test
docker compose --project-name mimirq --env-file .env -f docker/docker-compose.lite.yml up -d --build
# 4) 健康检查
curl http://localhost:8000/api/v1/health/ready      # -> {"ok":true,"status":"ready"}
```

```bash
# 5) 登录（字段是 identifier，不是 username/email）
curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier":"admin","password":"<INITIAL_ADMIN_PASSWORD>"}'
#   -> {"user":{...},"token":{"access_token":"...","expires_in":1800}}
# 6) 建 dataset（记下 UUID）
curl -s -X POST http://localhost:8000/api/v1/datasets/ -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"name":"course-1","description":"..."}'
# 7) 灌入课程资料（file_type 用扩展名、不带点！）
curl -s -X POST http://localhost:8000/api/v1/documents/manual \
  -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" --data-binary @body.json
#   body: {"dataset_id":"<uuid>","filename":"m3.txt","file_type":"txt",
#          "file_size":246,"chunks":[{"content":"...","page_number":1}]}
# 8) 回填 DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON 后重启 api
```

### 5.4 本轮新踩的坑（编号接上一节）

- **坑12：下载包是嵌套目录且路径含中文。** `E:\浏览器下载\MimirQ-main\MimirQ-main`（zip 解压多一层），
  内容与 `D:\mimirq-test` 完全相同（3623 vs 3624 文件）。**用 `D:\mimirq-test`**：
  它已有 `.env`、路径是纯 ASCII（Docker 构建上下文/bind mount 对非 ASCII 路径有风险）、且是 git clone 可 `git pull`。
- **坑13：Docker Desktop 没运行。** 报错是 `failed to connect to the docker API at npipe://.../dockerDesktopLinuxEngine`。
  先 `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`，再轮询
  `docker version --format '{{.Server.Version}}'` 等守护进程就绪，**不要马上构建**。
- **坑14（好消息）：Docker Hub 其实拉得动。** `redis:7` / `postgres:15` 均拉取成功，
  方案文档第九节最担心的"国内拉不动基础镜像"没有发生。
- **坑15：首次构建在 HF 模型下载处失败，但那是瞬时故障。** 报错
  `huggingface_hub.errors.LocalEntryNotFoundError: ConnectError: [Errno 101] Network is unreachable`，
  而同一时刻容器访问 `hf-mirror.com` 与 `huggingface.co` 都是 200。**直接重试即可**（pip 层有缓存，很快到失败点）。
- **坑16：模型下载在构建期，而 `HF_ENDPOINT` 没有传进去。** Dockerfile 里只有 `HF_HOME`，
  没有 `ARG/ENV HF_ENDPOINT`；compose 的 `build.args` 也只传了两个构建元数据。
  所以 `.env` 里的 `HF_ENDPOINT=https://hf-mirror.com` **对构建无效**，容器直连 huggingface.co（会吃到 429 限流）。
  若再次因 429/网络失败，需在 Dockerfile 补 `ARG HF_ENDPOINT` + `ENV HF_ENDPOINT` 并在 compose 传值。
- **坑17：`INITIAL_ADMIN_EMAIL` 缺失导致容器启动崩溃。**（进度文档那句"`INITIAL_ADMIN_*` 别漏"就是说这个）
  校验要求 `USERNAME` + `EMAIL` + **恰好一个**密码来源三者齐全，否则
  `ValidationError: INITIAL_ADMIN bootstrap requires INITIAL_ADMIN_EMAIL, INITIAL_ADMIN_USERNAME, and exactly one password source`。
- **坑18：`email_validator` 拒绝 `.local` 这类特殊用途域名。** 填 `admin@mimirq.local` 会报
  `INITIAL_ADMIN_EMAIL must be a valid email address`（`.local`/`.test`/`.invalid`/`.localhost` 都被拒）。用 `admin@mimirq.com`。
- **坑19：登录字段是 `identifier`，不是 `username`/`email`；token 在 `token.access_token` 下。**
  发 `{"username":...}` 会 422；响应结构是 `{"user":{...},"token":{"access_token":...}}`。
- **坑20：`/documents/manual` 的 `file_type` 要"扩展名不带点"，不是 MIME。**
  代码是 `file_type_with_dot = f".{request.file_type.lower()}"` 再比对 `allowed_extensions_list`；
  传 `text/markdown` → 400 `Unsupported file type`，传 `txt` → 201。
- **坑21（最费时间的坑）：PowerShell 5.1 的 `Invoke-RestMethod` 会挂死，curl 秒回。**
  同一个 POST，`Invoke-RestMethod` 等满 150s 无响应，`curl --max-time 60` **8~14ms 返回 201**。
  更坑的是 PowerShell 超时被杀时**会丢掉已缓冲的输出**，看起来像"服务端卡死"，实际服务端一直是好的。
  **结论：调这些 HTTP 接口一律用 curl**，并用 `-w "HTTP=%{http_code} T=%{time_total}"` 记录耗时。
- **坑22：mysql 客户端不加 `--raw` 会破坏 JSON。** `JSON_OBJECT()` 产出的 `\n` 会被客户端反转义成真换行，
  导致"一行一个 JSON"变成多行、`ConvertFrom-Json` 报"应为 ':' 或 '}'"。加 `--raw` 解决。
- **坑23：PowerShell 5.1 不支持 `??`**；也不要把 hashtable 传给函数再 `ConvertTo-Json`
  （会产出非法 body，服务端报 `json_invalid: Extra data`）——**显式拼 JSON 字符串最稳**。
- **坑24：Dify 端点用的是合成账号 `system:dify`，而它不在 `tenant_members` 里。**
  见 `external_api.py:1045`。表现为 403 `Not a tenant member`。
  修法：设 `DIFY_EXTERNAL_KNOWLEDGE_ACCOUNT_ID` = 一个真实存在的租户成员 user_id。
- **坑25：MimirQ 的 Dify 响应**不透传**上传时写在 chunk 上的自定义 metadata。**
  返回的 `metadata` 只有它自己的字段（`document_id`/`chunk_id`/`chunk_index`/`hit_type`…），
  我上传时写的 `material_id` 拿不到。所以**不能靠 metadata 做资料映射**，
  只能靠 `title`（文档名）。→ 上传时**文档名就用资料标题**，别用 `m3.txt` 这种。
  （更稳的做法是上传后记录 `document_id → material_id` 的映射存到 Java 侧配置里，
  但那引入配置耦合；用有意义的文档名更自然。）
- **坑26：文档名带扩展名，标题匹配会失配。**
  MimirQ 返回的 `title` 是 `数据保护三大原则详解.txt`，而本地 `byTitle` 的键是无扩展名的资料标题。
  需要在匹配前剥掉尾部扩展名（`replaceAll("\\.[A-Za-z0-9]{1,6}$", "")`）。
- **坑27：MimirQ 的 access token 只有 30 分钟（`expires_in: 1800`）。**
  脚本跑久一点就会 `AUTHENTICATION_ERROR: Token expired`，
  且**表现是"操作全部静默失败"**（我这边一度以为删除和上传都没生效）。
  长脚本要重新登录，或把 token 过期单独判断出来。
- **坑28：接了外部知识库之后，"CHAIN 指标反而变 0" 是一个有价值的信号，不是退化。**
  首次把 CHAIN 指向 MimirQ 时 golden 掉到 `hitRate=0.0000` ——
  因为 MimirQ 命中无法映射回 `material_id`（坑25/26），评测按 materialId 比对自然全 miss。
  **这正是评测的价值**：它当场指出"映射断了"，而不是让人误以为"接了 MimirQ 就变好了"。
  映射修好后即回到 `1.0000`。

### 5.5 下一步

前 3 步已完成（403 已解决、Java 已接入、指标已恢复 1.0000）。**剩下的核心是"质量"而不是"连通"**：

1. **换真实 embedding（当前最大的质量短板）。**
   现在用的是 `deterministic_test` —— 哈希向量，**排序没有语义意义**。
   注意：实测中发现 MimirQ 的 `deterministic_test` 排序**并非完全随机**，
   它仍能排对（对话里三题 top-1 都对），但那更像是 BM25/词面通道在起作用，
   而不是语义。**要真正验证"语义检索"，必须换成真模型**：
   - 改 `EMBEDDING_PROVIDER=openai_compatible` + `EMBEDDING_MODEL=BAAI/bge-m3`
     + `EMBEDDING_API_BASE=https://api.siliconflow.cn/v1` + `EMBEDDING_API_KEY=<SiliconFlow key>`
   - **必须重建索引**（MimirQ 硬要求：换 embedding 模型后已有向量全部失效）
   - 重建后重跑 `/rag/eval`，对比 `deterministic_test` 与 `bge-m3` 两轮指标
2. **把 MimirQ 的评估能力用起来**：MimirQ 自带 RAGAS / 回归门禁（方案文档阶段五提到的），
   可以和我这边 20 题的 HitRate/Recall/MRR 做交叉验证。
3. **补解析层**：`tra_material` 里 DOCUMENT/VIDEO 两类没有正文，
   接 MimirQ 的解析管道（DeepDoc/MinerU）才能真正解决——这是召回缺口的根因。
4. **Java 侧产物清理**：`agent/tools/MimirQRetriever.java` 是并行开发产生的、
   **完全没有接线**的重复实现（只有 `@Slf4j`，无 `@Component`/`@Bean`，全项目 0 处引用），
   与现在的 `rag/mimirq/MimirQClient` + `RagRetrievalFacade` 功能重叠，建议删除以免误导。
