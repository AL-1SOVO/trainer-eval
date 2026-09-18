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

### P0-2 换真实 embedding 并重建索引

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

### P2-1 Agent 任务规划与反思重试

**现状**：Agent 会调工具，但没有 P0.2（任务拆解）与 P0.3（反思重试）。

**改动点**

| 能力 | 设计 |
|---|---|
| 规划 | 复杂问题先产出结构化步骤表（`[{step, tool, status}]`），通过 SSE `plan` 事件推给前端，前端渲染步骤清单 |
| 状态跟踪 | 步骤状态写 Redis（与现有 `agent:hist:*` 同源），支持中断后继续 |
| 反思重试 | 工具抛异常/返回错误时，把**结构化错误**回填给模型（而不是静默返回空），由模型决定换参数或换工具；设重试上限防打转 |
| 触发条件 | 简单问题（单工具可答）不进入规划，避免为展示而规划 —— 否则延迟和 token 都白涨 |

**验收标准**：
- 输入"帮我生成一份补训方案"→ 出现 ≥3 步规划，且每步状态可见
- 人为让一个工具失败 → Agent 自动换策略重试并成功，全过程可追溯
- 简单问题不产生规划开销（对比改造前后延迟）

**工作量**：1.5~2 天

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
