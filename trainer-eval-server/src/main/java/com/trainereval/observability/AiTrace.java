package com.trainereval.observability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次链路追踪的本地落库记录。
 *
 * <h3>为什么要有这张表（而不是只发 Langfuse）</h3>
 * <ol>
 *   <li><b>不依赖外部服务</b>：Langfuse 自托管要拉起 Postgres + ClickHouse + Redis + MinIO，
 *       对一套培训系统来说太重。本地表让"链路可见"这件事零依赖地成立。</li>
 *   <li><b>导出可能失败</b>：网络、鉴权、配额任何一环出问题，Langfuse 侧就是空白。
 *       本地表保证"数据一定在"，Langfuse 只是更好看的看板。</li>
 *   <li><b>可被业务查询</b>：管理端页面直接读这张表，不需要给前端发 Langfuse 的密钥。</li>
 * </ol>
 *
 * <h3>字段设计取舍</h3>
 * 只把<b>可检索的维度</b>建成列（name / user_id / session_id / status / latency / tokens），
 * 把易变的结构（每一步的明细）塞进 {@code detail} 的 JSON 里。
 * 理由：明细的字段几乎一定会随功能演进变化，建列等于每次都要改表；
 * 而"按用户查""按耗时排序""找失败的"是稳定需求，值得建列 + 索引。
 */
@Data
@TableName("ai_trace")
public class AiTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 32 位十六进制 traceId，与导出到 Langfuse 的 trace 一一对应，便于两边对照。 */
    private String traceId;

    /** 链路名，如 agent.chat / rag.retrieve / ai.grade / error.analysis。 */
    private String name;

    private Long userId;

    private String sessionId;

    /** 整条链路的耗时（毫秒）。 */
    private Integer latencyMs;

    /** 模型调用的累计 token，用于成本观察。 */
    private Integer promptTokens;

    private Integer completionTokens;

    /** 观测条目数（模型调用 + 检索 + 工具）。 */
    private Integer observationCount;

    /** DEFAULT / WARNING / ERROR。 */
    private String level;

    private String statusMessage;

    /** 输入摘要（截断）。 */
    private String inputBrief;

    /** 输出摘要（截断）。 */
    private String outputBrief;

    /** 全部观测明细的 JSON，结构随功能演进，不建列。 */
    private String detail;

    /** 是否成功导出到 Langfuse。0=未导出（未配置或失败），1=已导出。 */
    private Integer exported;

    private String exportError;

    private LocalDateTime createTime;

    public static final String LEVEL_DEFAULT = "DEFAULT";
    public static final String LEVEL_WARNING = "WARNING";
    public static final String LEVEL_ERROR = "ERROR";
}
