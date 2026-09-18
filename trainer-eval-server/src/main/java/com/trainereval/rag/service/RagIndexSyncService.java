package com.trainereval.rag.service;

import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mimirq.MimirQIngestClient;
import com.trainereval.training.entity.TraMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资料改动后同步"两套索引"：
 * <ol>
 *   <li><b>本地切块</b>（{@code rag_chunk}）—— 系统记录的事实，决定本地混合检索能不能搜到；</li>
 *   <li><b>MimirQ 知识库</b> —— 可替换的派生索引，决定混合召回+重排的质量。</li>
 * </ol>
 *
 * <h3>为什么本地写入必须用 {@code REQUIRES_NEW}</h3>
 * 这两步是在业务事务<b>提交之后</b>执行的。如果直接用默认的 {@code REQUIRED}，
 * Spring 会把写入"挂"在已经提交、但资源尚未释放的那个事务上 ——
 * 而它的 Javadoc 明确写了此时<b>不会再有 commit 跟随</b>，
 * 也就是说写进去的数据可能根本不落库（"看着成功了，其实没保存"）。
 * 用 {@code REQUIRES_NEW} 显式开一个自己的事务，落库才是确定的。
 * <p>
 * 顺带解决另一个坑：{@code REQUIRES_NEW} 会挂起外层事务，
 * 因此这里抛异常<b>不会</b>把外层业务事务标记成 rollback-only ——
 * "索引失败"再也不会把"资料保存"一起拖垮（而单纯 try/catch 是挡不住的）。
 *
 * <h3>为什么 HTTP 推送不放在事务里</h3>
 * 推送给 MimirQ 是网络调用（最长 20s 超时）。把它包进事务会让数据库连接被占住整个网络等待时间，
 * 高并发下直接把连接池耗干。所以 {@link #pushToMimirQ} / {@link #removeFromMimirQ}
 * 是<b>无事务</b>的，只读已提交的切块再发 HTTP。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIndexSyncService {

    private final ChunkingService chunkingService;
    private final RagChunkMapper ragChunkMapper;
    private final MimirQIngestClient ingestClient;

    public static final String PUSH_OK = "OK";
    public static final String PUSH_SKIPPED = "SKIPPED_NOT_CONFIGURED";
    public static final String PUSH_DELETED = "DELETED_EMPTY_CONTENT";

    /**
     * 最近一次"推送到 MimirQ"的结果，按 materialId 记录。
     * <p>
     * 为什么需要它：推送发生在<b>事务提交后的回调</b>里，调用方（上传接口）拿不到它的返回值。
     * 而"到底有没有同步进知识库"正是用户提交资料后最想知道的一件事 ——
     * 若这里回显"已同步"而实际失败了，等于骗人。所以把结果留在这里供调用方回查。
     * 有界：超过 {@value #MAX_STATUS_ENTRIES} 条就整体清空（这是可重建的派生状态，丢了不影响正确性）。
     */
    private static final int MAX_STATUS_ENTRIES = 500;

    private final Map<Long, String> lastPushStatus = new ConcurrentHashMap<>();

    /** 查询某条资料最近一次灌库结果：OK / SKIPPED_NOT_CONFIGURED / DELETED_EMPTY_CONTENT / FAILED: ... */
    public String lastPushStatus(Long materialId) {
        return materialId == null ? null : lastPushStatus.get(materialId);
    }

    private void recordPushStatus(Long materialId, String status) {
        if (materialId == null) {
            return;
        }
        if (lastPushStatus.size() >= MAX_STATUS_ENTRIES) {
            lastPushStatus.clear();
        }
        lastPushStatus.put(materialId, status);
    }

    /** 独立事务内重建本地切块。返回写入的块数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rebuildLocalChunks(TraMaterial material) {
        return chunkingService.rebuildMaterial(material);
    }

    /** 独立事务内清掉本地切块。返回删除行数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removeLocalChunks(Long materialId) {
        return ragChunkMapper.deleteByMaterialId(materialId);
    }

    /**
     * 把本地已提交的切块推给 MimirQ（覆盖式）。
     * 未配置写入账号时静默跳过 —— 这时系统仍能靠本地混合检索工作，只是拿不到重排质量。
     */
    public void pushToMimirQ(Long materialId, Long courseId, String title) {
        if (!ingestClient.isConfigured()) {
            recordPushStatus(materialId, PUSH_SKIPPED);
            return;
        }
        try {
            List<String> chunks = ragChunkMapper.listContentsByMaterial(materialId);
            if (chunks.isEmpty()) {
                // 正文被清空/本来就没有正文：把知识库里的旧文档也删掉，否则会搜到已删内容
                ingestClient.deleteMaterial(materialId, courseId);
                recordPushStatus(materialId, PUSH_DELETED);
                return;
            }
            ingestClient.upsertMaterial(materialId, courseId, title, chunks);
            recordPushStatus(materialId, PUSH_OK);
        } catch (RuntimeException e) {
            // 记录失败原因后继续往上抛：调用链上层的 try/catch 负责只记警告、不影响业务
            recordPushStatus(materialId, "FAILED: " + e.getMessage());
            throw e;
        }
    }

    /** 资料被删除时同步删除 MimirQ 侧文档。 */
    public void removeFromMimirQ(Long materialId, Long courseId) {
        if (!ingestClient.isConfigured()) {
            return;
        }
        ingestClient.deleteMaterial(materialId, courseId);
        lastPushStatus.remove(materialId);
    }
}
