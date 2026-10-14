# AI Agent 升级计划（2026 秋招版）

> 目标：把 trainer-eval 从「单轮 prompt 调用」升级为完整的多 Agent 智能体项目，
> 对标 2026 校招 AI Agent 开发岗要求（自主规划 / RAG / 多智能体编排 / MCP / Function Calling）。
>
> **参考项目：[MimirQ](https://github.com/skygazer42/MimirQ)**（Apache 2.0，中文优先企业 RAG 知识库）
>
> ⚠️ **本文档已于 2026-09-18 逐条对照代码重新核对。**
> 此前所有复选框都是 `[ ]`，但其中一大批其实早已实现 —— 这个偏差直接导致过一次误判
> （"Agent 不会调用工具"）。**勾选状态现在只代表"代码里确实有、且我核对过"**，
> 每条已完成的都注明了证据位置。核对方法与历次偏差见文末§附录。
>
> ⚠️ **2026-10-12 第二次核对。** 09-18 那次核对之后，09-19 的提交
> `918ceb3 feat: 知识库管理页 + 链路追踪(Langfuse) + MCP 接入 + 多智能体协作 + 评分一致性`
> 一次性落地了 4 个能力，但**本文档没有跟着改**，于是 P2 / P3 / P5.12 又变成过期状态。
> 这次把它们改回与代码一致。**这已经是同一个错误犯第二次**——见文末§附录的教训。

## 当前基线（已完成，升级的出发点）

- [x] DeepSeek 接入（OpenAI 兼容协议，token/成本/延迟记录）
- [x] 主观题 AI 批改（规则配置 + prompt 模板 + JSON 解析容错）
- [x] 错题归因分析（错误分类 + 学习建议）
- [x] 人工复核流程（AI 建议分 → 人工改分 → 总分重算）
- [x] AI 评分日志页（状态筛选 + 详情抽屉）

> ✅ **已修复（2026-10-12）**：默认模型名此前写的是 `deepseek-v4-flash`，
> 而 DeepSeek 实际只提供 `deepseek-flash` / `deepseek-v4-pro`，请求只是被网关
> **宽容路由**到了 `deepseek-flash`（属于"碰巧能用"，且会让
> `ai_grading_log.model` 记错模型名）。现已全部改为 `deepseek-flash`，共 5 处：
> `application.yml` 的 `spring.ai.openai.chat.options.model` 与 `openai.model`、
> `OpenAIConfig.java` 的字段默认值、`AiGradingController.java` 的 `DEFAULT_MODEL`、
> `AiGradingServiceImpl.java` 里兜底规则 `rule.setModel(...)`。
> 注意**只改默认值不改覆盖点**：线上仍可用 `DEEPSEEK_MODEL` 环境变量覆盖。

---

## P0 Agent 核心能力（简历上"Agent"二字的支撑）

### 1. 工具调用循环 Function Calling Loop — ✅ 已完成

- [x] 引入 Spring AI，替换手写 OpenAIClient
      （证据：`AgentChatService` 用 `ChatModel` + `OpenAiChatOptions`；
      旧的 `OpenAIClient` 仍服务于批改链路，两条并存）
- [x] 定义并注册业务工具：`myExamRecords`（成绩）、`myWrongAnswers`（错题）、
      `myEnrolledCourses`（课程）、`searchCourseMaterials`（检索课程资料）、
      `getMaterialDetail`（展开资料全文）
      （证据：`agent/tools/TutorTools.java`，全部带 `@Tool` 注解）
- [x] 实现 Agent 主循环：模型决定调用 → 执行 → 结果回填 → 继续推理
      （证据：`internalToolExecutionEnabled(false)` + `ToolCallingManager.executeToolCalls`，
      默认最多 6 轮：`agent.tutor.max-iterations`）
- [x] 落地场景：学习辅导 Agent —— 实测输入"我最近考试考得怎么样"，
      第 1 轮并行调用 `myExamRecords` + `myWrongAnswers`，
      第 2 轮调用 `searchCourseMaterials` ×2，随后产出带 `M4#0` 引用的答案
- [x] 面试要点自测：工具 schema 由 `ToolCallbacks.from(tools)` 从注解生成；
      结果以 `ToolResponseMessage` 回填；终止条件是"本轮响应不含 tool_calls"
      或达到 `maxIterations`（超限时明确报错而不是静默截断）

### 2. 任务规划与分解 Planning — ✅ 已完成（2026-09-18）

- [x] 复杂任务先拆解为步骤列表再逐步执行
      （证据：`agent/service/AgentPlanner.java` 产出结构化步骤表；
      `AgentChatService` 通过 SSE `plan` 事件下发，`step_status` 逐个回报状态；
      前端渲染成带状态的执行计划面板）
- [x] 步骤执行状态持久化，支持中断后继续
      （证据：计划与状态写入 Redis `agent:plan:{userId}:{sessionId}`，TTL 同会话历史；
      下一轮若存在未完成步骤，会作为上下文交给模型继续）

> 设计取舍：规划**不是每次都做**。`agent.tutor.planning-mode` 默认 `auto` 走启发式门
> （短消息/闲聊不规划），因为对"我考了多少分"这类单步问题规划纯属为展示而增延迟。
> 门只是粗筛，模型仍可返回空计划来否决。规划失败会自动退化为原来的"走一步看一步"，
> 不影响对话本身。

### 3. 反思与自我修正 ReAct / Retry — ✅ 已完成（2026-09-18）

- [x] 工具执行失败或输出解析失败时，Agent 自主决定重试策略而非直接兜底
      （证据：`agent/service/AgentReflection.java` 诊断工具返回，
      命中"空结果/报错"时以 SystemMessage 追加**具体**的重试指引
      —— 换更宽泛的词 / 换同义表达 / 拆词，并把 `reflection` 事件推给前端；
      单工具重试上限 `agent.tutor.max-tool-retries`=2，用尽后转为"如实告知"指引）

> 关键设计：**区分"该重试"与"重试无意义"**。
> 检索类工具（参数是关键词）空结果值得换词重试；
> 成绩/错题类工具空结果是"确实没数据"，重试只会白烧 token，直接如实说明。
>
> 实测（问语料里不存在的 Kubernetes）：
> ```
> event:reflection x2  {"tool":"searchCourseMaterials","reason":"资料检索无结果","retrying":true,"attempt":1}
>                      {"tool":"searchCourseMaterials","reason":"资料检索无结果","retrying":true,"attempt":2}
> ```
> 最终回答明确指出"仅命中一份无关资料 M4#0《常见网络安全威胁与防范指南》…未涉及 Helm 或容器编排，
> 非你想要的知识点"，并说明该知识点尚未上传 —— 而不是拿网络安全资料硬答 Kubernetes。

---

## P1 RAG 课程知识库

> 📌 详细落地方案见 **《企业级RAG实施方案.md》**；实施记录与坑见 **《RAG实施进度与踩坑.md》**；
> 后续优化路线见 **《RAG与Agent优化方案.md》**。

> **实现路线**：已选**路线 A** —— 部署 MimirQ 作为独立知识库服务，Java 端接入，源码已 vendored 进 `mimirq/`。

### 4. 知识库构建

- [x] 课程资料解析为纯文本
      （证据：`training/service/DocumentTextExtractor.java` —— txt/md/csv/json/xml/html（自动探测 UTF-8/GBK）、
      docx（段落+表格）、pptx（逐页+表格）、xlsx（逐 sheet）、pdf（文本层）；
      **扫描件需 OCR，当前会明确拒绝建资料而不是静默产生无正文条目**）
- [x] 分块 chunking（证据：`rag/service/ChunkingService.java` —— 段落优先、句子兜底、80 字重叠、剥 HTML；
      **不走 MimirQ 的 86 种策略**，是用自研切块结果作为 manual chunks 灌入）
- [ ] Embedding 入库 —— ⚠️ **当前是 `deterministic_test` 哈希向量，排序无语义**。
      要换真模型见《RAG与Agent优化方案.md》P0-2（需 embedding 服务 key；DeepSeek 无 embedding API）

### 5. 检索与生成

- [x] Docker Compose 部署 MimirQ（证据：`mimirq/docker/docker-compose.lite.yml`，
      由 `start.ps1` 托管生命周期，实测 15s 就绪）
- [x] Spring AI 的 `ToolCallback` 封装 MimirQ 检索 API，注册为 Agent 工具
      （证据：`searchCourseMaterials` → `RagRetrievalFacade`）
- [x] 混合检索：向量 KNN + 关键词 + 重排
      （证据：链路 `MimirQ → 本地 ngram 全文 + 子串 + RRF → 子串兜底`；
      重排由 MimirQ 承担，但**13 种 reranker 尚未做对比实验**）
- [x] 错因分析 / 学习建议改为基于检索到的真实资料片段回答
      （证据：`ai/service/impl/AiErrorAnalysisServiceImpl.java` 已注入
      `private final RagRetrievalFacade retrievalFacade`（L42）并在 L168 调用
      `retrievalFacade.search(...)`，返回的 `RagResult` 参与建议生成；
      对应提交 `8d6e682 feat(ai): 错因分析接入 RAG——建议基于真实课程资料，
      并修掉"牵强关联无关资料"`）
- [x] 引用溯源：回答中标注引用编号
      （证据：SSE `citation` 事件 → 前端「参考来源」卡片；`M12#0` 格式可跳回课程）
- [x] 权限对齐（部分）：`KnowledgeAccessService` 实现课程级裁剪，
      **降级路径同样裁剪**（避免 fail-open），支持 `PUBLISHED` / `ENROLLED` 两档
- [ ] 部门级 ACL（MimirQ 支持文档级 ACL，尚未与部门/角色体系打通）

### 6. 检索效果验证

- [x] 构建 golden set：20 条"问题 → 应命中资料"标注对
      （证据：`resources/rag/golden-rag.jsonl`，其中 11 条与原文无字面重叠）
- [x] 召回命中率 / MRR 指标 + 回归门禁
      （证据：`/api/v1/rag/eval`，CHAIN 与 LIKE_BASELINE 双策略留档在 `rag_eval_run.strategy`；
      **2026-09-18 复测：CHAIN 1.0000 vs LIKE 0.4500（+0.55）**）

> ⚠️ **2026-10-14 复测更正**：上面这段的 `LIKE_BASELINE = 0.4500`（以及"提升 +0.55"）
> 在本仓库当前代码 + 当前 13 份教学语料下**复现不出来**。`rag_eval_run` 表里已落的评测
> 显示基线一直是 **0.8500**，真实提升是 **+0.15**。根因是 `GoldenEvalService.likeBaselineMaterials`
> 后来改成**先用同一套 `normalizeQuery` 清洗再做子串匹配** —— 去停用词后
> "数据保护三大原则是什么"退化成"数据保护三大原则"，正好等于资料 3 的标题，于是直接命中。
> 基线变强本身是**对**的（原先拿未清洗的问题去 LIKE 不公平），但文档没跟着改，
> 于是留下了一个被夸大约 3.7 倍的提升幅度。**上面这张表保留原样作为历史记录，请勿再引用其数字。**
> 现行口径（含 `ChunkingService` 段落切分修复后 MRR 由 1.0000 变为 0.9667 的原因）见 [README.md](README.md) 的「实测数据」一节。
> 复现：`POST /api/v1/rag/eval?topK=5`。

- [ ] 对比不同 chunk 策略 / 是否 rerank 的效果差异，形成一页实验结论

---

## P2 多智能体协作 — 🟡 部分完成

> ⚠️ **2026-10-12 更正**：本节此前写着"⬜ 全部未完成"，
> 并附了一句"已核实：代码中不存在任何多 Agent 编排实现"。
> **这个结论是错的** —— `agent/service/MultiAgentOrchestrator.java` 早在
> 提交 `918ceb3`（2026-09-19）就已存在，而那份 09-18 的核对结论被一直留到了 10-12。
> 过期原因见文末§附录的教训。**"多智能体"这一项的现状是：框架与一条链路已实现，
> 但考试域的四个角色 Agent 尚未实现** —— 两者不要再混为一谈。

### 7. 角色化多 Agent

- [x] **至少一条完整协作链路可演示：资料员 → 复核员 → 汇报员**
      （证据：`agent/service/MultiAgentOrchestrator.java`；
      三段角色提示词 `RESEARCHER_PROMPT` / `CRITIC_PROMPT` / `WRITER_PROMPT`；
      角色常量 `ROLE_RESEARCHER` / `ROLE_CRITIC` / `ROLE_WRITER`，
      由 `roleCatalog()` 对外暴露；入口是 `POST /api/v1/agent/chat/stream`
      带 `mode=multi`；每段以 `sendRoleStart` / `sendRoleDone` 推 SSE，
      并各自 `trace.agent(...)` 记一个 span）
- [x] 分工设计：**复核员刻意不给工具**
      （证据：资料员带完整工具循环（与单智能体同一份 `TutorTools` + 同一批 MCP 工具），
      产出 `record Research(String answer, List<String> facts,
      List<Map<String,Object>> citations, ...)`；
      复核员只拿到"问题 + 取证清单"，输出【无据】/【缺口】两类判定；
      汇报员被要求"标为【无据】的内容一律不写"。
      动机：**同一个模型在同一份上下文里自查是弱校验** —— 它已经花了几轮工具调用，
      倾向于认为自己查全了，所以复核必须换一个没参与检索的上下文）
- [x] 成本取舍与默认档位有记录
      （证据：三角色各是一次完整模型调用，实测一轮 ≈30s / 11266+5440 token，
      约为单智能体的 3~4 倍，**所以不是默认模式**；
      `agent.multi.max-research-iterations`=4（低于单智能体的 6，
      因为多智能体的重点是分工而不是更深的检索）、`history-window`=10、
      `history-ttl-hours`=168）
- [ ] 出题 Agent：根据岗位画像 / 课程大纲生成试题入库
      （`questionbank/` 目前只有题库管理，没有自动出题）
- [ ] 批改 Agent：现有评分逻辑 Agent 化
      （现状是 `AiGradingService` 单轮调用，不是 Agent）
- [ ] 诊断 Agent：错因归因 + 知识图谱式薄弱点关联
- [ ] 学习规划 Agent：生成个性化补训计划
- [ ] 考试域协作链路：考试提交 → 批改 → 诊断 → 规划
      （已实现的三角色链路服务于"**学习问答**"取证与校验，
      **不是**这条考试域链路，两者用途不同，不要互相顶替）

> 两个链路的区别值得记住：三角色链路解决的是"**这个回答有没有说得太满**"，
> 考试域链路解决的是"**这个人接下来该练什么**"。前者已有，后者没有。

---

## P3 MCP 工具生态 — 🟡 部分完成

- [x] Agent 接入通用 MCP Client，工具列表动态扩展
      （证据：`mcp/` 包共 11 个类 —— `AbstractMcpClient`(13003B)、
      `StdioMcpClient`(11168B)、`HttpMcpClient`(6585B)、
      `McpToolRegistry`(16129B)、`McpToolCallback`(6139B)、
      `McpProperties`(4619B)、`McpController`(3419B) 等；
      自实现 JSON-RPC 2.0，**stdio 与 Streamable HTTP 双传输**
      （含 SSE 响应解析、会话头回带）；外部工具经 `McpToolCallback`
      自动进入模型工具列表。配置见 `application.yml` 的 `mcp:` 段，
      `max-tools: 64` 防止提示词被工具定义撑爆，
      内置零依赖演示 server `mcp-demo-server/demo_mcp_server.py` 用于验证链路）
- [x] 三条安全边界写进配置而不是靠自觉
      （证据：① server 只能来自配置文件，`McpController` 刻意没有增删接口 ——
      stdio 传输等价于本机执行命令，开放动态注册=开放远程命令执行；
      ② `allowed-roles` 留空即仅管理员（示例中为 `MANAGER`）——
      MCP 工具没有用户身份，做不到本地工具那样的行级过滤，
      默认取最保守值把"忘记配置"的后果从"数据泄露"降级为"功能没生效"；
      ③ 工具结果统一包 `<data>` 标签。
      默认 `MCP_ENABLED=false`，没配 server 时行为与没有这个功能完全一致）
- [ ] 把「AI 批改」「错因分析」封装为 MCP Server 暴露出去
      （当前仓库里的是 MCP **客户端** + 一个演示用 server，
      业务能力本身还没有 server 化）

---

## P4 记忆与会话

### 10. 短期记忆 — ✅ 已完成

- [x] 学习辅导 Agent 支持多轮连续追问
      （证据：`AgentChatService.loadHistory/appendHistory`，Redis key `agent:hist:{userId}:{sessionId}`，
      窗口 `agent.tutor.history-window`=20 条，TTL 168 小时）

### 11. 长期记忆 / 学习者画像 — ⬜ 未完成

- [ ] 跨考试累积学生历史薄弱点，形成画像，供规划 Agent 使用

---

## P5 可观测与评估体系

### 12. 全链路追踪 — ✅ 已完成

- [x] 自研 Span 体系 + 本地留档
      （证据：`observability/Observation.java` 定义五类 span
      `TYPE_CHAIN`/`TYPE_GENERATION`/`TYPE_RETRIEVER`/`TYPE_TOOL`/`TYPE_AGENT`；
      `Observability.java`(13435B)、`LlmTrace.java`(9665B)、`AiTrace.java`(2885B)；
      主路径写本地表 `ai_trace`（`db/ai_trace.sql`），**零外部依赖**）
- [x] 接入 Langfuse，作为可选的第二出口
      （证据：`observability/LangfuseExporter.java`(23469B) +
      `LangfuseProperties.java`(4211B)；`application.yml` 的 `langfuse:` 段
      默认 `LANGFUSE_ENABLED=false`，配了才发；**异步、有界队列
      （`queue-capacity: 2000`、`batch-size: 20`）、失败只计数不影响业务**；
      默认走 `LANGFUSE_MODE=otlp`（POST `/api/public/otel/v1/traces`），
      自托管 < v3.22.0 可切 `legacy`）

> 与 09-18 判断的差异：当时写的"接入 Langfuse 替换手写 `ai_grading_log`"
> 实际做法是**并存而非替换** —— 本地表永远是主路径，Langfuse 只是可选导出。
> 这个选择更稳：可观测性本身不应该成为新的故障点。

### 13. 效果评估 — 🟡 部分完成

- [x] RAG 侧 golden 回归门禁（同 P1 §6）
- [x] 指标：AI 分与教师分一致性
      （证据：`ai/service/AiGradingConsistencyService.java`(15515B) ——
      `report(Integer sinceDays)` 输出 `mae` / `rmse` / `pearson` / `biasDirection`，
      并按题型拆分（客观题与主观题的"AI vs 教师"一致性差异极大，合并看会互相掩盖）；
      Pearson 在 `n < 3` 或任一侧方差为 0 时返回 null 而不是 NaN/±1 这种假极端值）
- [ ] 标注 50~100 道题的人工评分作为 golden set
- [ ] Agent 任务完成率
- [ ] 换 prompt / 模型时自动跑评估集做回归对比

---

## P6 工程化加固

### 14. 体验 — ✅ 已完成

- [x] SSE 流式输出：Agent 执行过程实时展示
      （证据：`tool_call` / `tool_result` / `citation` / `delta` / `done` / `error` 六类事件；
      前端渲染「正在调用 xxx…」→「xxx 完成」）
- [ ] 考试提交后批改进度推送（替换前端轮询）

### 15. 稳定性 — 🟡 部分完成

- [ ] token 预算与并发限流（已核实：无 RateLimiter；仅有单条规则的 `max_tokens` 上限）
- [x] LLM 输出格式容错（批改链路的 JSON 解析容错 + 失败不写 0 分）
- [x] Prompt 注入防护（证据：`AgentGuard.checkInput` 在进入模型前拦截；
      工具结果统一用 `<data>` 包裹形成可依赖的边界；`maskOutput` 做输出脱敏）

### 16. 安全收尾 — 🟡 部分完成

- [x] AI/知识库密钥外部化（`application.yml` 用 `${DEEPSEEK_API_KEY:...}` 等占位，
      实际值走 `.env.local` 或系统环境变量，`.env.local` 已在 .gitignore）
- [ ] JWT 密钥 / 数据库密码外部化（仍硬编码在 `application.yml`）
- [ ] 轮换历史泄露的 DeepSeek key（明文曾出现在 `target/` 构建产物里）

---

## 简历最终叙事（做完后用一句话串起来）

> 基于 Spring AI 构建多 Agent 企业培训考核系统：Java Agent 编排 + MimirQ 中文知识库
> （混合检索 + 重排序，golden set 召回命中率 X%）+ MCP 工具生态 + Langfuse 全链路可观测
> + RAGAS/golden 回归评估，AI 批改与教师评分一致性达 X%，
> 支持「出题-批改-诊断-补训规划」多 Agent 协作闭环，文档级 ACL 与企业权限体系打通。

**当前缺口**（2026-10-12 更新）：
1. **考试域多智能体** —— 出题 / 批改 / 诊断 / 学习规划四个角色 Agent，
   以及「考试提交 → 批改 → 诊断 → 规划」链路。
   （"多智能体"本身**不是**缺口：资料员→复核员→汇报员已实现，见 P2）
2. **业务能力 MCP Server 化** —— 现在是 MCP 客户端，批改 / 错因分析还没暴露成 server。
3. **标注 50~100 道题的人工评分 golden set** —— 一致性指标已实现，但缺样本。
4. **P1.4 embedding** —— 仍是 `deterministic_test` 哈希向量。
   其中 **embedding 是质量短板**（其余是能力覆盖面的缺口）。

**已从缺口移除**：P3 MCP 客户端、P5.12 Langfuse —— 都在 09-19 已实现，此前是文档没跟上。

---

## 附录：文档偏差记录与核对方法

### 第一次核对（2026-09-18）发现"文档说未完成、代码已完成"的项

| 项 | 文档原状 | 实际 |
|---|---|---|
| P0 §1 工具调用循环 | `[ ]` | ✅ 完整实现，实测 4 次工具调用 |
| P1 §4 切块 / §5 混合检索 / 引用 / 权限 | `[ ]` | ✅ 已实现 |
| P1 §6 golden set 与指标 | `[ ]` | ✅ 20 题 + 双策略留档 |
| P4 §10 短期记忆 | `[ ]` | ✅ Redis 会话窗口 |
| P6 §14 SSE | `[ ]` | ✅ 六类事件 |
| P6 §15 注入防护 | `[ ]` | ✅ `AgentGuard` |

### 第二次核对（2026-10-12）：同一次提交造成的 4 处过期

提交 `918ceb3`（2026-09-19）一次落地了 4 个能力，文档一处都没跟：

| 项 | 核对前的文档原状 | 实际（证据） |
|---|---|---|
| P2 §7 多智能体 | "⬜ 全部未完成"，且称"代码中不存在任何多 Agent 编排实现" | ✅ 存在 `agent/service/MultiAgentOrchestrator.java`（28141B），资料员→复核员→汇报员三角色链路可演示 |
| P3 MCP | "⬜ 未完成" | 🟡 MCP 客户端已完成（`mcp/` 11 个类，stdio + Streamable HTTP）；仅"业务能力 server 化"未做 |
| P5 §12 Langfuse | "⬜ 未完成"，且称"代码中无 langfuse 集成" | ✅ `observability/LangfuseExporter.java`(23469B) + `langfuse:` 配置段 |
| P5 §13 一致性指标 | `[ ]` | ✅ `ai/service/AiGradingConsistencyService.java`(15515B)，Pearson / MAE / RMSE |
| P1 §5 错因分析接 RAG | `[ ]`，且称"未引用 `RagRetrievalFacade`" | ✅ 已注入并在 L168 调用（提交 `8d6e682`） |
| 基线·默认模型名 | 隐患提示"建议改为 `deepseek-flash`" | ✅ 已改，共 5 处（见文首） |

**这次和上次的差别值得注意**：09-18 那次是"**文档一直没写过实现**"，
这次是"**文档写对了、后来又过期了**"。后者的危害其实更大 ——
过期的那句话带着"已核实"三个字，比没写更让人信。

**核对方法**（可复现）：对每条复选框，找"能证伪的代码位置"——
`@Tool` 注解、`@Transactional` 传播行为、Mapper 方法、Controller 路由、
以及**实际跑一次并看事件流/日志**。只靠读文档或读一个类名都会误判。

**教训**：文档里的复选框也是代码资产。它过期一次，就会让下一个人（包括作者自己）
在错误的前提下做判断。此后的规则是：**改代码的同时改文档，同一提交内完成。**

**补充教训（2026-10-12）**：光有"同一提交内完成"这条规则还不够，因为
`918ceb3` 那次**正是没有遵守它**。所以再加一条可执行的检查：
**凡是有"已核实"字样的结论，必须带核对日期**；核对日期早于最近一次
涉及该模块的提交时，这句"已核实"就作废，当作未核实处理。
