# RAG 与 Agent 优化方案（trainer-eval × MimirQ）

> 编写时间：2026-09-18
> 依据：对当前代码库**实测**得到的数据（非估算），以及
> 《企业级RAG实施方案.md》《RAG实施进度与踩坑.md》《AI-AGENT-升级计划.md》。
> 所有"现状"结论都注明了证据来源。

---

## 一、结论摘要

系统当前**功能已通、质量未达标、体验有断点**。三个真正的瓶颈，按影响排序：

| 排序 | 瓶颈 | 一句话说明 | 证据 |
|---|---|---|---|
| **1** | ~~**解析层缺失**~~ → ✅ **已修复** | 上传 PDF/Word/PPT 只存盘不抽正文，这类资料永远进不了索引。检索质量的上限被"资料有没有正文"锁死 | 修复前 `uploadMaterial()` 只做 `Files.copy` 返回 fileUrl；现由 `DocumentTextExtractor` + `MaterialIngestService` 补齐（详见 P0-1） |
| **2** | **embedding 是哈希向量** | `deterministic_test` 无语义，`relevance` 数值不可解释，向量通道几乎不产生有效信号 | `mimirq/.env`: `EMBEDDING_PROVIDER=deterministic_test`、`EMBEDDING_API_BASE=` 为空 |
| **3** | **Agent 只有工具调用、没有规划与纠错** | 能调工具（已验证），但不会拆解复杂任务、工具失败也不会自己改策略重试 | 代码中 grep 无 planning / reflect / retry 相关实现 |

> 注：上传入口只解决"新资料能进索引"。**存量**的 DOCUMENT/VIDEO 类资料正文仍为空，
> 需要各自重新上传，或接入 MimirQ 的 OCR 解析后端（扫描件场景）。

**重要澄清**：Agent **不是**"停留在 LLM 阶段"。实测证据见 §2.2。

---

## 二、现状基线（实测）

### 2.1 RAG 检索链路

| 指标 | 实测值 | 备注 |
|---|---|---|
| CHAIN（MimirQ + 本地混合） | hitRate / recall / MRR = **1.0000** | 20 题 Golden 集 |
| LIKE_BASELINE（旧的 `LIKE '%kw%'`） | **0.4500** | 同题集 |
| 提升 | **+0.5500** | 三个指标一致 |

> ⚠️ **2026-10-14 复测更正**：上面这段的 `LIKE_BASELINE = 0.4500`（以及"提升 +0.55"）
> 在本仓库当前代码 + 当前 13 份教学语料下**复现不出来**。`rag_eval_run` 表里已落的评测
> 显示基线一直是 **0.8500**，真实提升是 **+0.15**。根因是 `GoldenEvalService.likeBaselineMaterials`
> 后来改成**先用同一套 `normalizeQuery` 清洗再做子串匹配** —— 去停用词后
> "数据保护三大原则是什么"退化成"数据保护三大原则"，正好等于资料 3 的标题，于是直接命中。
> 基线变强本身是**对**的（原先拿未清洗的问题去 LIKE 不公平），但文档没跟着改，
> 于是留下了一个被夸大约 3.7 倍的提升幅度。**上面这张表保留原样作为历史记录，请勿再引用其数字。**
> 现行口径（含 `ChunkingService` 段落切分修复后 MRR 由 1.0000 变为 0.9667 的原因）见 [README.md](README.md) 的「实测数据」一节。
> 复现：`POST /api/v1/rag/eval?topK=5`。

| 端到端延迟 | **484 ms** | `/rag/eval` 整体 |
| 自动灌库耗时 | **449 ms** | 含 MimirQ 登录 + 建库 + 写文档（本地 Docker） |
| 检索服务方 | `MIMIRQ` / `MIMIRQ+LOCAL_HYBRID` / `LOCAL_HYBRID` | 记在 `rag_retrieval_trace.strategy` |

链路：`MimirQ → 本地混合(ngram 全文 + 子串 + RRF) → 子串兜底`，MimirQ 命中不足 topK 时用本地补齐，
`strategy` 字段记录实际服务方，复盘时能区分"知识库没召回"和"降级了没查"。

### 2.2 Agent（这块要先澄清）

用有效 key 实测 `/api/v1/agent/chat/stream`，输入"我最近考试考得怎么样？"：

```
event:tool_call   x4    iteration1: myExamRecords + myWrongAnswers
                        iteration2: searchCourseMaterials ×2
event:tool_result x4
event:citation    x1    M4#0 →「常见网络安全威胁与防范指南」
event:delta       x28 → event:done
```

**工具调用循环是工作的**：`@Tool` 注解完整、`OpenAiChatOptions.internalToolExecutionEnabled(false)`
+ 手动执行循环、SSE 推送 `tool_call/tool_result`、前端 `AiTutor.vue` 渲染「正在调用 xxx… → 完成」标签。

已验证 4 个模型名（`deepseek-flash` / `deepseek-v4-pro` / `deepseek-chat` / `deepseek-reasoner`）
**全部支持 tool_calls**。

> **容易被误判为"不会调工具"的三种情况**（排查清单）：
> 1. **文档在骗人**：`AI-AGENT-升级计划.md` 的 P0 复选框全是 `[ ]`，但代码已实现。见 §5。
> 2. **key 没注入**：实测过一次 `HTTP 401 - Your api key: ****ured is invalid` ——
>    进程里 `DEEPSEEK_API_KEY` 为空时 Spring 会用 yml 默认值 `not-configured`（正好以 `ured` 结尾）。
>    IDEA 直接跑而不配环境变量就会这样，表现为 agent 报"学习助手暂时不可用"，**不是**不调工具。
> 3. **问题本身不需要工具**：纯闲聊/通用知识问题，模型直接答是**正确行为**。
>
> **另一个真实隐患**：yml 默认模型 `deepseek-v4-flash` **不在** DeepSeek 的模型列表里
> （实际只有 `deepseek-flash`、`deepseek-v4-pro`），现在只是被宽容路由了 —— 运气好，不是配置对。

---

## 三、优化路线

### P0-1 解析层：上传即抽正文并自动建索引 — ✅ 已完成（2026-09-18）

**为什么是第一优先**：这是"检索质量"的物理上限。没有正文的资料，任何 embedding / rerank 都救不回来。

**现状**：内训师上传文件后拿到 `fileUrl`，必须手动把正文粘贴进「资料内容」框，否则资料无正文 → 无切块 → 搜不到。

**改动点**

| 层 | 改动 |
|---|---|
| 后端新增 | `DocumentTextExtractor`：按扩展名分派抽取器 |
| | `.txt/.md/.csv/.json` → 直接读（自动探测 UTF-8 / GBK） |
| | `.docx` → POI `XWPFDocument`（段落 + 表格单元格） |
| | `.pptx` → POI `XSLFSlide` 逐页文本 |
| | `.xlsx` → POI `XSSFWorkbook` 逐 sheet 逐行 |
| | `.pdf` → iText `PdfTextExtractor`（按页） |
| 后端新增 | `POST /api/v1/training/chapters/{chapterId}/materials/ingest`（multipart + title） |
| | 复用 `uploadMaterial` 存盘 → 抽正文 → 建 `TraMaterial`（content=正文）→ 返回抽字数/切块数/灌库结果 |
| 前端 | `CourseEditor.vue` 加「上传并建索引」按钮，回显"抽取 N 字 / 切 M 块 / 已同步知识库" |
| 复用 | 已有的 `afterCompletion` 钩子会自动完成本地切块 + MimirQ 灌库，**不需要新写** |

**依赖**：`poi-ooxml`、`poi-scratchpad`、`itext7-core` **已在 pom 中**，无需新增依赖。

**验收标准**
- 上传一份真实 PDF → `tra_material.content` 非空 → `rag_chunk` 有行 → MimirQ 出现 `m{id}__*.txt`
- 员工在「知识搜索」搜该 PDF 里的**独有短语** → 命中，且 `citation` 的 `materialId` 正确
- 不支持的类型（如 `.zip`）返回明确提示，而不是静默产生一条无正文资料

**工作量**：0.5~1 天

**实际交付与实测结果**

| 项 | 结果 |
|---|---|
| `DocumentTextExtractor` | 支持 txt/md/csv/json/xml/html（自动探测 UTF-8/GBK）、docx（段落+表格）、pptx（逐页+表格）、xlsx（逐 sheet）、pdf（文本层） |
| `MaterialIngestService` + `POST /training/chapters/{id}/materials/ingest` | 抽正文 → 建资料 → 切块 → 灌库，一步完成 |
| `GET /training/materials/ingest/capabilities` | 前端提示可用格式与 50MB 上限 |
| 前端 | `CourseEditor.vue` 章节上新增「上传并建索引」，回显 抽取字数/解析段数/切块数/知识库同步状态 |
| 实测（真 docx） | 抽取 450 字、解析 8 段、切块 2 块、`knowledgeSync=SYNCED` |
| 实测（员工检索） | 搜上传文档的独有短语 `pdfplumber` → 命中 `[M9#0]`，`strategy=MIMIRQ+LOCAL_HYBRID` |
| 实测（降级） | Docker 停机时上传 → 本地索引成功、`knowledgeSync=FAILED` 如实回显、员工靠 `LOCAL_HYBRID` 仍能搜到 |

**两个刻意的设计决定**
1. **抽不到正文就拒绝建资料**（而不是建一条没有正文的条目）。后者只会制造"看着有、搜不到"的假象。
2. **灌库失败必须如实回显 FAILED**，不能因为"大部分情况会成功"就默认报成功 ——
   回显"已同步"却其实失败，比不回显更糟。为此在 `RagIndexSyncService` 里按 materialId 记录了
   最近一次推送结果（有界，500 条整体清空），供上传接口回查真实状态。

---

### P0-2 换真实 embedding 并重建索引 — ✅ 已完成（2026-09-18）

**为什么是第二**：`deterministic_test` 是哈希向量，**排序无语义**。它现在"看起来能对"，
是因为混合检索里的 BM25/词面通道在起作用，不是向量在起作用。
换句话说：**当前 1.0000 的指标证明不了语义检索有效**，只证明了词面通道 + 引用映射是通的。

**实测佐证**：`score_threshold: 0.0` 时，一个完全不相关的问题也能拿到 **0.59** 分
（正确问题 0.77）—— 区分度极低，这正是无意义向量的典型表现。

**改动点**

| 项 | 内容 |
|---|---|
| `mimirq/.env` | `EMBEDDING_PROVIDER=openai_compatible`<br>`EMBEDDING_MODEL=BAAI/bge-m3`<br>`EMBEDDING_API_BASE=https://api.siliconflow.cn/v1`<br>`EMBEDDING_API_KEY=<key>` |
| 重建 | **必须全量重建**（MimirQ 硬要求：换模型后已有向量全部失效）<br>① 重新灌库：删除 dataset 内文档 → 从 `rag_chunk` 重新推送<br>② 或清空 `vector_data` 卷后重灌 |
| 验证 | 重跑 `/rag/eval`，对比 `deterministic_test` 与 `bge-m3` 两轮指标；<br>用"无字面重叠"的改写型问题（现 Golden 集里有 11 条）单独看差异 |
| 调参 | 有了真向量再调 `score_threshold`（现在设 0.4，但语料太短时无意义） |

**前置条件**：**需要用户提供 embedding 服务 key**（SiliconFlow / 智谱 / 或本地 BGE）。
DeepSeek **没有** embedding API，不能复用现有的 DEEPSEEK_API_KEY。

**验收标准**：一份实验记录，含两轮指标对比 + 至少 3 个"只有语义向量才能命中"的案例。

**工作量**：0.5 天（不含等待 key）

---

#### 操作手册（key 到位后按此执行，已把"重建"做成接口）

> 为什么必须有独立的"重建"步骤：MimirQ 的硬要求是**换 embedding 模型后已有向量全部失效**，
> 而它没有单独的"重建"按钮 —— 做法就是**重新灌入文档**（先删后建会触发重新向量化）。
> 日常上传/修改会自动同步，但**换模型这一天，存量资料不会自己重来一遍**。

```powershell
# ① 改 embedding 配置（mimirq/.env）
EMBEDDING_PROVIDER=openai_compatible
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_API_BASE=https://api.siliconflow.cn/v1
EMBEDDING_API_KEY=<你的 key>
EMBEDDING_DIM=<与模型匹配的维度，bge-m3 为 1024>

# ② 重启 MimirQ（让新配置生效）
.\stop.cmd -KeepMimirQ:$false        # 或 docker compose -f .\mimirq\docker\docker-compose.lite.yml restart
docker compose -f .\mimirq\docker\docker-compose.lite.yml up -d

# ③ 全量重推，触发重新向量化
#    POST /api/v1/rag/knowledge-base/resync   （MANAGER 权限）
#    会逐条把有正文的资料重新灌入 MimirQ；先删后建，不产生重复文档

# ④ 巡检确认服务端事实与本地期望一致
#    GET  /api/v1/rag/knowledge-base/status

# ⑤ 重跑 Golden 评测，对比两轮指标
#    POST /api/v1/rag/eval   （结果留档在 rag_eval_run.strategy）
```

**已交付的配套接口**（本次实现，不依赖 key 即可用）：

| 接口 | 作用 |
|---|---|
| `POST /api/v1/rag/knowledge-base/resync` | 全量重推：把有正文的资料重新灌入 MimirQ；返回 pushed/skipped/failed 与失败明细 |
| `GET /api/v1/rag/knowledge-base/status` | 巡检：MimirQ 侧实际有哪些库、各多少文档，与本地期望对比即可发现漏灌的课程 |

实测（2026-09-18，用当前 `deterministic_test` 配置走通全流程）：
`status` 显示 MimirQ `course-1` 有 2 文档、本地期望 2 条资料，一致；
`resync` 返回 `pushed=2, skipped=0, failed=0`；重推后检索仍命中 `[M3#0]`/`[M4#1]`，`strategy=MIMIRQ`。

> 重推过程中发现并修掉一个真实缺陷：清理旧文档时只删了带 `m{id}__` 前缀的，
> 早期手工灌库的**裸标题文档**残留下来，导致同一份资料在知识库里存在两份
> （实测重推后文档数从 2 变成 4）。已改为同时清理"文件名去扩展名 == 资料标题"的历史文档。

---

#### ✅ 实际执行结果（2026-09-18，未使用任何外部 key）

原计划用云端 `bge-m3`（需要 key）。实际改用了**本地 sentence-transformers 模型**，
因为验证发现：容器能访问 HuggingFace，且镜像里已装 `sentence-transformers` ——
**不需要任何 key 就能换成真实语义模型**，比等 key 更快也更好（无外部依赖、无调用成本）。

配置：`EMBEDDING_PROVIDER=local`、`EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5`（95MB / 512 维 / 中文优化）。

**关键指标对比**

| 配置 | CHAIN hitRate | CHAIN recall | CHAIN MRR | 语料外问题 |
|---|---|---|---|---|
| `deterministic_test`（哈希向量）+ 阈值 0.4 | 1.0000 | 1.0000 | 1.0000 | 返回 2 条无关资料（0.60+），助手据此编造引用 |
| `bge-small-zh-v1.5` + 阈值 0.4 | 1.0000 | 1.0000 | **0.9500** | 仍返回无关资料 |
| `bge-small-zh-v1.5` + 阈值 0.7 + min-score 过滤 | 1.0000 | 1.0000 | **1.0000** | **返回空，如实告知"未找到"** |

> 第一行的 1.0000 与第三行的 1.0000 含义完全不同：前者是**词面通道**在 20 题单跳集上的成绩
> （向量无意义），后者才包含真实的语义排序。**指标相同不等于能力相同**，这是本轮最值得记的一点。

**分数分布（决定阈值的关键数据）**

```
golden 题命中最高分：min 0.6000 / p25 0.6700 / 中位 0.7400 / max 0.8100
语料外问题最高分：    0.6000 ~ 0.6300
```

两者在 **0.60~0.63 区间重叠** —— 所以**不存在一个能干净区分"相关/不相关"的阈值**。
0.7 之所以可用，不是因为它在语义上"正确"，而是因为它高于噪声上限，
且被它滤掉的 golden 命中**由本地混合检索补了回来**（这正是链路设计的价值）。

**行为对比（同一个问题："课程资料里有没有讲 Kubernetes 容器编排的内容？"）**

| | 修复前 | 修复后 |
|---|---|---|
| 检索结果 | 2 条（`M4#0` 网络安全资料，score 0.6+） | 0 条 |
| 引用事件 | 有（引用了一份无关资料） | **无** |
| 回答 | 拿网络安全资料硬答，并给出可信的引用编号 | "课程资料中暂未找到相关内容……检索后端 MIMIRQ 未降级，说明是知识库本身无此内容……无法给出 citation 出处" |
| 反思 | 无（因为检索从不返回空） | `reflection` x3：换词重试 2 次后如实收尾 |

**代价（如实记录）**
- 延迟：评测整体 484ms → 1530ms（本地推理约 +50ms/查询）。
- 阈值 0.7 偏高，当前部分查询依赖词面通道兜底。**语料变大后必须用同样方法重测分数分布再调**，
  配置文件里已写明这个再调优触发条件，并支持 `MIMIRQ_SCORE_THRESHOLD` 覆盖。
- 模型缓存放在 `upload_data` 卷里（`HF_HOME=/data/uploads/hf_cache`），容器重建不会丢失。

---

### P1-1 灌库异步化

**现状**：上传请求会同步等待 MimirQ（本地 449 ms 可接受）。接远程 MimirQ 或批量导入时，
上传接口会被网络拖长，且失败后没有补偿。

**改动点**：`RagIndexSyncService.pushToMimirQ` 改为投递到队列（项目已有 Redis），
worker 消费 + 失败重试 + 死信记录；新增"待同步/已同步/失败"状态字段或表。

**验收标准**：上传接口 P95 < 300 ms（不等 MimirQ）；MimirQ 停机时上传仍成功，恢复后自动补齐。

**工作量**：1 天

---

### P1-2 MimirQ 切块策略 / reranker 对比实验

**现状**：当前把 `rag_chunk` 的切块直接作为 manual chunks 灌给 MimirQ，
**等于绕过了 MimirQ 的 86 种切块策略**；13 种 reranker 一个都没启。

**改动点**：设计 3~4 组对照（自研切块 vs MimirQ 策略 / 开 rerank vs 关），
用同一 Golden 集跑指标，写成一页结论。

**验收标准**：一页实验报告，明确"哪种组合在当前语料上最好、为什么"。
这是面试深挖区，比指标本身更有价值。

**工作量**：1~2 天

---

### P2-1 Agent 任务规划与反思重试 — ✅ 已完成（2026-09-18）

**现状**：~~Agent 会调工具，但没有 P0.2（任务拆解）与 P0.3（反思重试）。~~

**实际交付与实测结果**

| 能力 | 实现 | 实测 |
|---|---|---|
| 规划 | `AgentPlanner` 产出结构化步骤表；SSE `plan` 下发，`step_status` 回报状态；前端渲染执行计划面板 | 输入"分析薄弱环节并给补训方案" → 4 步计划，3 个工具步骤全部 DONE，推理步骤 DONE |
| 状态持久化 | 计划+状态写 Redis `agent:plan:{userId}:{sessionId}`，下一轮注入未完成步骤 | 已实现（中断续做） |
| 反思重试 | `AgentReflection` 诊断工具返回；空结果/报错时注入**具体**重试指引（换宽泛词 / 换同义词 / 拆词），SSE `reflection` 下发 | 问语料里不存在的 Kubernetes → `reflection` x2，模型换词重试后如实说明"仅命中一份无关资料…非你想要的知识点" |
| 触发条件 | `planning-mode: auto` 走启发式门（短消息/闲聊不规划）；模型可返回空计划否决 | 简单问题不产生规划开销 |
| 重试预算 | 单工具上限 2 次；用尽后转为"如实告知"指引 | 只对检索类工具重试；成绩/错题类空结果不重试（确实是没数据） |

**实测中修掉的两个自身缺陷**
1. 纯推理步骤（`tool=null`，如"汇总生成方案"）被错误标成 `SKIPPED` —— 而它的产出就是最终回答。
   已改为：本轮产出了答案就算 DONE，只有真正没跑到的工具步骤才标 SKIPPED。
2. `ToolResponse.responseData()` 返回的是**被 JSON 再编码过**的字符串（内含 `\"materials\":[]` 转义），
   导致按 `"materials":[]` 匹配的反思逻辑永远不触发。已改为先还原 JSON 字符串字面量再匹配。
   —— 这个缺陷是**跑出来的**，不是看代码看出来的。

**工作量**：1.5~2 天（实际约 0.5 天）

---

### P2-2 其他文档已列但未做的能力

| 项 | 文档位置 | 建议 |
|---|---|---|
| MCP Server/Client | `AI-AGENT-升级计划.md` P3 | 成本低、辨识度高，可做 |
| Langfuse 全链路追踪 | P5 §12 | 差异化亮点，替换手写 `ai_grading_log` |
| 多智能体编排 | P2 §7 | 工作量最大，**性价比最低**，建议放到最后 |
| 部门级 ACL | `企业级RAG实施方案.md` §67 | 现在只有 PUBLISHED/ENROLLED 两档，属加分项 |
| Golden 集扩容（多跳/改写） | P5 §13 | 20 题全是单跳，评测区分度不够 |

---

## 四、明确不做的（工程判断）

写下来是为了避免反复讨论：

1. **不在 Java 侧自研一套向量检索**。MimirQ 是可替换的派生索引，事实源是 MySQL。
   自研会同时失去"可替换"和"可回归"两个属性。
2. **不把 MimirQ 做成硬依赖**。它是检索质量增强层，挂了必须能降级到本地混合检索。
   （`start.ps1` 里 MimirQ 启动失败只警告不退出，就是这个设计。）
3. **不为展示而规划**。Agent 规划只在多步任务时触发，简单问题直接答。
4. **不追求把 Golden 刷到 1.0000**。当前 1.0000 已经说明评测集**区分度不足**，
   继续优化指标是自欺欺人，先扩样本再谈提升。

---

## 五、文档与代码不一致清单（待修）

这是**本次误判"Agent 不会调工具"的直接原因**，单独列出：

| 文档 | 位置 | 现状 | 实际 |
|---|---|---|---|
| `AI-AGENT-升级计划.md` | P0 §1 全部 | `[ ]` 未完成 | ✅ 已实现（Spring AI + 工具循环 + 4 个业务工具 + SSE） |
| `AI-AGENT-升级计划.md` | P1 §4/§5/§6 | `[ ]` 未完成 | ✅ 已实现（切块、混合检索、引用溯源、权限裁剪、Golden 评测） |
| `AI-AGENT-升级计划.md` | P4 §10 短期记忆 | `[ ]` 未完成 | ✅ 已实现（Redis 会话窗口 + TTL） |
| `AI-AGENT-升级计划.md` | P6 §14 SSE | `[ ]` 未完成 | ✅ 已实现 |
| `企业级RAG实施方案.md` | M1/M2 | ⬜ 未完成 | ✅ 实际上已完成（MimirQ 已部署、资料已入库、自动灌库已通） |
| `企业级RAG实施方案.md` | M5 指标 | CHAIN 1.0000 vs LIKE **0.4000**（+0.60） | 复测为 **0.4500**（+0.55）—— 基线随数据变动，需注明复测日期 |

> ⚠️ **2026-10-14 复测更正**：上面这段的 `LIKE_BASELINE = 0.4500`（以及"提升 +0.55"）
> 在本仓库当前代码 + 当前 13 份教学语料下**复现不出来**。`rag_eval_run` 表里已落的评测
> 显示基线一直是 **0.8500**，真实提升是 **+0.15**。根因是 `GoldenEvalService.likeBaselineMaterials`
> 后来改成**先用同一套 `normalizeQuery` 清洗再做子串匹配** —— 去停用词后
> "数据保护三大原则是什么"退化成"数据保护三大原则"，正好等于资料 3 的标题，于是直接命中。
> 基线变强本身是**对**的（原先拿未清洗的问题去 LIKE 不公平），但文档没跟着改，
> 于是留下了一个被夸大约 3.7 倍的提升幅度。**上面这张表保留原样作为历史记录，请勿再引用其数字。**
> 现行口径（含 `ChunkingService` 段落切分修复后 MRR 由 1.0000 变为 0.9667 的原因）见 [README.md](README.md) 的「实测数据」一节。
> 复现：`POST /api/v1/rag/eval?topK=5`。


> 教训：**文档里的复选框也是代码资产**。过期文档会直接导致错误的判断，
> 这次就是典型案例（用户据此认为 Agent 没做工具调用）。

---

## 六、建议实施顺序

```
P0-1 解析层 + RAG 上传   ← 解决"资料进不了索引"，收益最大
   ↓
P0-2 换真 embedding       ← 需要 key；解决"排序无语义"
   ↓
文档对齐（§5）             ← 顺手做完，避免继续误判
   ↓
P2-1 Agent 规划/反思     ← 从"会调工具"到"会规划"
   ↓
P1-1 灌库异步化 / P1-2 reranker 对比
   ↓
P2-2 MCP / Langfuse
```

每项完成后：更新本文件状态 + 提交 git + 重跑 `/rag/eval` 留档对比。
