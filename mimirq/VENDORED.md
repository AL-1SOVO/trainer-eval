# MimirQ（已纳入本仓库）

本目录是 **MimirQ v1.0.1 的源码快照**，作为本项目的知识库/RAG 引擎一起做版本管理。

## 来源与版本

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/skygazer42/MimirQ |
| 快照 commit | `5d08b54`（"Remove the newly disclosed NLTK exposure before release"） |
| 版本 | v1.0.1 |
| 导入方式 | 下载的源码压缩包解包后整体复制（**不是** git submodule） |
| 导入日期 | 2026-09-16 |
| 许可证 | Apache License 2.0（见 `LICENSE` / `NOTICE`） |

导入时做了**逐字节校验**：3623 个文件 / 182,747,577 字节，与上游压缩包解包结果完全一致
（`main.py`、`docker/Dockerfile`、`docker/docker-compose.lite.yml`、`.env.example`、`requirements.txt`
的 SHA256 全部一致）。也就是说这里是**未修改的上游代码**，本项目的集成都做在
`trainer-eval-server` 侧，没有改动 MimirQ 源码。

## 为什么放进仓库（而不是保持独立目录 / submodule）

- 一个仓库包含全部组件：clone 下来就是完整系统，不依赖"另外去下载一个 MimirQ"。
- 不依赖联网：submodule 需要 clone/更新时能访问 GitHub，而本机网络不稳
  （部署时 HuggingFace 模型下载就失败过）。
- 代价是仓库体积：本目录约 **172 MB**（其中 `app/deepdoc/resources/` 约 112 MB，
  是上游自己 checked-in 的 NLTK 语料与中文分词模型资源）。

> 如果以后要压缩体积：可以把 `app/deepdoc/resources/` 从 git 里摘出去（它已被上游用于离线解析），
> 但这会让"全新 clone → 构建镜像"少掉解析所需资源，需要额外补一份下载脚本。**当前选择是不摘**：
> 自包含优先于体积。

## 怎么跑

由项目根目录的 `start.cmd` / `start.ps1` 统一管理（会自动 `docker compose up -d` 并等待就绪）：

```powershell
.\start.cmd              # 起后端(8080) + 前端(5173) + MimirQ(8000)
.\start.cmd -SkipMimirQ  # 只起后端+前端，检索自动降级为本地混合检索
.\start.cmd -MimirQBuild # 改了 mimirq/ 下源码或依赖后，强制重建镜像
.\stop.cmd               # 停全部（MimirQ 只停容器、保留数据卷）
```

手工操作：

```powershell
docker compose -f .\mimirq\docker\docker-compose.lite.yml up -d
docker compose -f .\mimirq\docker\docker-compose.lite.yml logs --tail 80 mimirq-api
```

## 首次使用必须做的一步：准备 `.env`

`mimirq/.env` 被 git 忽略（含密钥），**全新 clone 后不存在**，需要：

```powershell
Copy-Item .\mimirq\.env.example .\mimirq\.env
```

然后至少填这几项（对照 MimirQ 上游 `.env.example` 的注释）：

| 变量 | 作用 |
|---|---|
| `SECRET_KEY` | JWT 签名密钥，必须改掉默认值 |
| `INITIAL_ADMIN_EMAIL` / `INITIAL_ADMIN_PASSWORD` | 引导管理员账号；**写入侧（自动灌库）要用它登录** |
| `DIFY_EXTERNAL_KNOWLEDGE_ENABLED=true` | 打开 Dify 兼容检索接口 |
| `DIFY_EXTERNAL_KNOWLEDGE_API_KEYS` | 检索用 API Key，对应 trainer-eval 的 `MIMIRQ_API_KEY` |
| `DIFY_EXTERNAL_KNOWLEDGE_MAP_JSON` | `knowledge_id → dataset UUID` 映射，如 `{"course-1":{"dataset_ids":["<uuid>"]}}` |
| `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` | 向量模型。默认 `deterministic_test` 是**哈希向量，排序无语义** |

## 与 trainer-eval 的接口约定

两端通过两个稳定约定耦合，改任何一边都要同步改另一边：

1. **`knowledge_id` = `course-{courseId}`**
   - 检索侧：`mimirq.knowledge-id-pattern`（`application.yml`）
   - 写入侧：dataset 名 = `course-{courseId}`，找不到就自动创建
2. **文档文件名 = `m{materialId}__{资料标题}.txt`**
   （见 `MimirQDocumentNaming`）—— 检索响应里**只有 `title` 可控且会原样回传**，
   所以 material_id 编进文件名，是唯一不依赖"标题唯一性"的映射方式。

## 升级上游

因为是源码快照（无 `.git`），升级 = 重新下载上游 → 覆盖本目录，然后：

1. 对比 `docker/`、`.env.example`、`requirements.txt` 的差异，确认集成约定未变
   （尤其是 `/api/v1/documents/manual`、`/api/v1/datasets`、Dify 检索接口的契约）；
2. `.\start.cmd -MimirQBuild` 重建镜像；
3. 重跑 RAG 回归：`POST /api/v1/rag/eval`（对比 `CHAIN` 与 `LIKE_BASELINE` 的 HitRate/Recall/MRR）。
