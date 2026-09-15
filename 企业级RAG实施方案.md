# 企业级 RAG 实施方案（trainer-eval × MimirQ）

> 参照 [MimirQ](https://github.com/skygazer42/MimirQ)（Apache 2.0，中文优先的企业 RAG 知识库）的
> 「可检查、可替换、可回归」知识流水线方法论，为 trainer-eval 设计的企业级 RAG 落地方案。
>
> MimirQ 的核心理念：**企业知识库真正难的，不是把文档向量化，而是让错误可定位、策略可替换、质量可回归。**
> 流水线：`数据评估 → 场景化解析 → 清洗治理 → 业务切块 → 向量/全文索引 → 混合召回 → 重排与引用 → Golden 回归`

---

## 一、现状与差距

| 环节 | trainer-eval 现状 | 企业级目标（MimirQ 方法论） |
| --- | --- | --- |
| 解析 | 上传文件仅存 file_url，正文靠手工填 content | 按文档类型选解析器（Office/PDF/OCR 分路），解析结果可检查 |
| 治理 | 无 | 清洗规则（去页眉页脚、乱码、敏感信息）可配置、可审计 |
| 切块 | 无切块概念，整篇 content 进 LIKE 查询 | 按业务结构切块（课程→章节→知识点），非固定长度窗口 |
| 索引 | MySQL `LIKE '%kw%'` | 向量 + BM25 双索引 |
| 召回 | 单路关键词，无相关性排序 | 混合召回（向量+全文）+ RRF 融合 |
| 重排 | 无 | Reranker 精排（bge-reranker 等） |
| 引用 | 无 | 回答标注"依据《课程》/章节/片段"，可溯源 |
| 权限 | 工具层按 userId 过滤（做得对，但资料无 ACL） | 检索前 security trimming，按报名关系裁剪 |
| 评测 | 无 | Golden 题集 + 回归门禁，效果可量化、可防退化 |

现有 Agent 工具 `searchCourseMaterials` / `getMaterialDetail` 的**接口签名保留不变**，
内部实现从 SQL LIKE 替换为 RAG 检索——前端与 Agent 循环零改动，这就是解耦带来的好处。

---

## 二、总体架构

```
┌────────────────────────── trainer-eval（Java 21 / Spring AI）──────────────────────────┐
│  AiTutor.vue（SSE 聊天）                                                                │
│      │ /api/v1/agent/chat/stream                                                        │
│  AgentChatService（工具循环 + Redis 会话记忆 + agent_message 审计）                      │
│      │ 工具调用                                                                         │
│  RagSearchTool（新增，替换原 SQL LIKE 工具）                                             │
│      │  ① 本地校验：用户报名了哪些课程（tra_enrollment）→ 允许检索的知识范围              │
│      │  ② HTTP 调 MimirQ 检索 API（混合召回 + 重排 + 引用）                              │
│      │  ③ 引用信息随 SSE 事件透传前端                                                    │
└──────────────┬─────────────────────────────────────────────────────────────────────────┘
               │ REST（Dify External Knowledge 兼容协议）
┌──────────────▼─────────────── MimirQ（Python/FastAPI，Docker 部署）─────────────────────┐
│  解析（DeepDoc/MarkItDown/MinerU…）→ 治理规则 → 业务切块 → Milvus 向量+BM25 索引        │
│  混合召回（向量+BM25+RRF）→ 重排（bge-reranker）→ 证据+引用返回                          │
│  Golden 题集 + RAGAS 评测 + 回归门禁（make core-e2e）                                   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

职责边界（面试要点）：**Java 负责 Agent 编排与业务权限，MimirQ 负责知识流水线质量**。
这正是企业里"Agent 系统"与"知识中台"分离的标准架构，避免把解析/切块/检索细节塞进业务系统。

---

## 三、阶段一：部署 MimirQ（0.5~1 天）

### 前置要求（来自 MimirQ README）

- Docker 20.10+ 与 Docker Compose 2.0+（你本机已装 Docker Desktop，需先启动）
- GNU Make；一键启动另需 Python 3.9+（仅用于生成配置）
- 资源：4 核 / 16 GB RAM / 50 GB 磁盘
- ⚠️ 网络注意：你当前校园网出口拦截了 api.deepseek.com。MimirQ 的 LLM/Embedding/Reranker
  都走 `EMBEDDING_API_BASE` 等独立配置，建议模型服务统一换成可直连的国内供应商（见下）

### 部署步骤

```bash
git clone --depth 1 --single-branch https://github.com/skygazer42/MimirQ.git
cd MimirQ
make init          # 只创建缺失的 .env / web/.env.local，不覆盖已有配置
# 编辑 .env（关键项见下表）
make up-web        # 完整栈：Postgres+Milvus+Etcd+MinIO+Redis+API+Worker+前端
make api-ping      # 验证 API 存活
```

机器资源吃紧时用 `make up-lite`（Chroma/FAISS 替代 Milvus、免 MinIO）先跑通闭环，再切完整栈。

### .env 关键配置

| 变量 | 说明 | 本项目建议 |
| --- | --- | --- |
| `LLM_API_KEY`（必填） | MimirQ 侧生成/治理用的 LLM | 用你现有的 key（供应商需当前网络可达） |
| `EMBEDDING_API_BASE/KEY/MODEL` | 独立 embedding 服务 | SiliconFlow `BAAI/bge-m3` 或智谱 `embedding-3`（中文效果好、国内可达） |
| `ENABLE_RERANKER` + `RERANKER_*` | 重排器 | `BAAI/bge-reranker-v2-m3`（与 bge-m3 同生态） |
| `INITIAL_ADMIN_*` | 首个管理员 | 初始化后公开注册会关闭，记好密码 |
| `DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON` | knowledge_id 映射 | 留到阶段三配置（见下） |

> 注意（README 原文提醒）：**更换 Embedding 模型后必须重建已有知识库索引**——先定模型再入库。
> 另一个企业细节：默认 PDF 解析可能走 PyMuPDF（AGPL-3.0），以 SaaS 形式对外提供服务时有开源合规风险，
> 需要规避时改用 pypdf/pdfplumber 解析后端。简历/面试提到这一点非常加分。

---

## 四、阶段二：课程知识入库（1~2 天）

### 2.1 数据集规划

按 MimirQ 的 dataset 组织知识，与 trainer-eval 的业务结构对齐：

```
dataset: course-{courseId}（每个课程一个知识库）
  └── 文档 = tra_material（一篇资料一个文档）
        ├── 富文本资料：content 直接入库
        └── 上传文件（PPT/Word/PDF）：走 MimirQ 解析管道，file_url 导入
```

一库一课的好处：权限裁剪可以在 Java 侧按课程粒度做路由（见阶段四），不必把 trainer-eval
的用户体系同步进 MimirQ，集成成本最低。

### 2.2 解析器选择（对照 MimirQ 建议）

| 你的资料场景 | 建议解析器 | 启动命令 |
| --- | --- | --- |
| 培训 PPT/Word（数字原生 Office） | 内置 DeepDoc / MarkItDown 轻量路径 | 无需额外容器 |
| 常规 PDF 讲义 | 内置 DeepDoc | 无需额外容器 |
| 扫描版制度文件（图片型 PDF） | PaddleOCR-VL（需 NVIDIA GPU） | `make up-paddlevl` |
| 表格/公式密集的技术规范 | MinerU pipeline（需 GPU） | `make up-mineru` |

校园项目以数字原生文档为主，内置 DeepDoc 起步即可，扫描件场景按需再加容器。

### 2.3 治理与切块策略

- **治理**：先跑 20 篇样本看解析输出，配清洗规则去页眉页脚/水印/重复空行；这一步在 MimirQ
  治理页面可视化检查（"解析是否丢了表格、治理是否漏了规则"——每步输入输出可检查是它的核心卖点）
- **切块**：不要用固定长度+重叠窗口。你的资料天然有结构（章节标题/要点编号），用
  **标题/章节切块**，`course_name + chapter_title` 作为 chunk 元数据——检索命中后引用信息直接可溯源
- 在 MimirQ 切片预览页面对比 2~3 种策略的实际效果再定稿，把对比结论写进项目文档（面试深挖区素材）

---

## 五、阶段三：Java 侧接入（1~2 天，核心工作量）

### 3.1 检索接口（Dify External Knowledge 兼容协议）

MimirQ 暴露的标准端点：`POST /api/v1/integrations/dify/retrieval`

```json
// 请求
{
  "knowledge_id": "course-1",          // DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON 中配置的映射
  "query": "数据保护三大原则",
  "retrieval_setting": { "top_k": 6, "score_threshold": 0.4 }
}
// 响应（MimirQ 返回证据数组，含 score、内容、引用元数据）
{ "records": [ { "content": "...", "score": 0.83, "metadata": { "title": "...", "chapter": "..." } } ] }
```

### 3.2 新增 RagSearchTool（替换现有 SQL LIKE 工具）

```
@Tool: searchKnowledge(query) —— 语义检索课程知识库
  实现：
  1. SecurityUtils.getCurrentUserId() → tra_enrollment 查已报名课程集合
  2. course-{id} 路由到对应 MimirQ dataset（只查有权课程）
  3. RestClient 调 retrieval API，超时 5s，失败降级返回"知识库暂不可用"
  4. 返回 JSON：[{title, chapter, excerpt, score}]，供模型引用
```

原 `getMaterialDetail` 保留（MimirQ 命中片段通常已够用，深度阅读场景仍走本地表）。

### 3.3 引用透传与 SSE 扩展

- `AgentChatService` 的系统提示词增加规则：**回答必须基于工具返回的资料片段，
  末尾列出引用的资料标题与章节**
- SSE 事件新增 `citation`：工具返回后把命中片段的元数据推给前端，
  AiTutor.vue 在回答下方渲染"参考来源"卡片（点击可跳转课程学习页）

### 3.4 配置项（application.yml）

```yaml
mimirq:
  base-url: http://localhost:8000
  api-key: ${MIMIRQ_API_KEY:}
  timeout: 5s
  top-k: 6
  score-threshold: 0.4
```

---

## 六、阶段四：权限对齐（Security Trimming，0.5 天）

企业级 RAG 与 demo 的分水岭。两层防线：

1. **Java 侧路由裁剪**（主防线）：工具执行前查 `tra_enrollment`，用户只能路由到
   已报名课程的 dataset——未报名课程的资料在检索阶段就不存在
2. **MimirQ 文档 ACL**（可选进阶）：MimirQ 原生支持文档级 ACL + Security Trimming，
   把部门/角色映射进去做第二层校验，防止路由配置错误导致越权

面试话术："Agent 工具默认拥有数据库全部权限，我们通过 enrollment 关系在**工具边界**
做 security trimming，LLM 无法构造越权查询"——这是 Agent 安全的必答题。

---

## 七、阶段五：评测与回归门禁（1 天，差异化亮点）

1. **构建 Golden 题集**：20~30 条 `问题 → 应命中的资料/章节` 标注对（从真实课程资料出题），
   存 `evaluation/golden-rag.jsonl` 进版本库
2. **跑基线**：
   - 现状基线：SQL LIKE 在同一题集上的召回命中率（预期很低，这就是你的优化空间叙事）
   - MimirQ 直连：混合检索、±rerank、不同切块策略各跑一轮，形成对比表
3. **RAGAS 指标**：召回命中率 / MRR / 忠实度（faithfulness）/ 答案相关性，
   MimirQ 内置 RAGAS 评测直接用
4. **回归门禁**：换 embedding 模型、调切块参数、升级 MimirQ 版本后重跑 golden，
   命中率跌破阈值即拦截发布（MimirQ 的 `make core-e2e` 与回归门禁机制可直接复用）

MimirQ 自己的 800 题实测（2026-07 复测）可以引用进你的答辩：**检索直连准确率 98.9%，
而 Dify 原生知识库仅 38.6%**——说明检索层的工程质量对最终效果的影响远大于编排层，
这正是单独建设知识流水线的理由。

---

## 八、里程碑与工作量

| 里程碑 | 内容 | 工作量 | 验收标准 | 状态（2026-09-15） |
| --- | --- | --- | --- | --- |
| M1 | MimirQ 部署 + 模型服务打通 | 0.5~1 天 | `make api-ping` 通过，能手动检索 | ⬜ 未完成（`.env` 的 `EMBEDDING_API_BASE` 为空；镜像从未构建） |
| M2 | 课程资料入库（解析+切块+预览确认） | 1~2 天 | 切片预览无语义断裂，引用元数据完整 | ⬜ 未完成（依赖 M1） |
| M3 | RagSearchTool + 引用透传 | 1~2 天 | AI 助手回答带可点引用，前端正常渲染 | ✅ **已完成**（走门面接入，MimirQ 不可用时自动降级；SSE `citation` + 前端「参考来源」卡片） |
| M4 | 权限裁剪 | 0.5 天 | 未报名课程的问题检索不到对应资料 | ✅ **已完成**（`KnowledgeAccessService`；实测纯 EMPLOYEE 检索 0 条，且**降级路径同样裁剪**） |
| M5 | Golden 评测 + 回归门禁 | 1 天 | 出一份基线 vs RAG 的指标对比表 | ✅ **已完成**（20 题 jsonl + 双策略留档；CHAIN 1.0000 vs LIKE 0.4000，**+0.60**） |
| 合计 | | **4~6 天** | | M3/M4/M5 已交付，M1/M2 待 MimirQ 侧完成 |

> 说明：M3/M4/M5 在 MimirQ 尚未可服务的情况下就完成了，靠的是把"知识库服务"设计成
> **可替换且可降级**的一级链路：`MimirQ → 本地混合检索 → 子串兜底`。
> 这样 Java 侧的接入、权限与评测可以独立于 MimirQ 的部署进度推进；
> 等 M1/M2 完成、`DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON` 回填后，
> **Java 侧无需改一行代码**即可自动切到 MimirQ。
> 详见《RAG实施进度与踩坑.md》第四节。

---

## 九、风险与降级

| 风险 | 应对 |
| --- | --- |
| 校园网拦截模型 API（已发生：deepseek 被拦） | embedding/reranker/LLM 全部走 `*_API_BASE` 可配置；换热点验证或换供应商；本地无法调通时 MimirQ 侧用 up-lite + 内网模型 |
| 机器资源不足（16G 内存跑不下完整栈） | `make up-lite`（Chroma/FAISS 替代 Milvus）先闭环，答辩演示够用 |
| MimirQ 服务不可用 | RagSearchTool 内置降级：5s 超时后回退原 SQL LIKE 并在回答中注明"降级检索" |
| 换 embedding 模型 | 必须重建索引（MimirQ 文档明确要求），所以模型选型在入库前定稿 |

---

## 十、简历/面试叙事

> 在 Spring AI 多 Agent 培训系统中集成 MimirQ 构建企业级 RAG 知识层：
> 按"解析→治理→业务切块→混合召回→重排→引用→Golden 回归"的知识流水线治理课程资料，
> 工具边界基于报名关系做 Security Trimming；自建 30 题 Golden 集做回归门禁，
> 检索命中率相对 SQL LIKE 基线提升 X%，AI 助手回答 100% 带可溯源引用。

高频追问的准备好答案：
- 为什么按课程分 dataset 而不是一个大库？（权限路由简单、爆炸半径小、索引可独立重建）
- 为什么 rerank 后还要引用？（可信度与可审计性；评测时能定位"召回漏了"还是"生成跑偏了"）
- 为什么不自己在 Java 里写 RAG？（对照 MimirQ 800 题数据：工程质量在流水线每一步，
  自研一个月写不出 30 解析器+86 切块策略+13 重排器；正确姿势是复用专业组件、聚焦业务集成）
