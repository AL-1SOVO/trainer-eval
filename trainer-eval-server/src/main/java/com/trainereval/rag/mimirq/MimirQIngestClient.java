package com.trainereval.rag.mimirq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MimirQ <b>写入</b>客户端 —— 把 trainer-eval 的资料推进知识库，让"内训师上传即可被检索"在
 * MimirQ 侧也成立（此前只有本地方案：切块写进 rag_chunk，MimirQ 侧靠手工灌库）。
 * <p>
 * 与 {@link MimirQClient}（检索，走 Dify 协议 + API Key）的区别：
 * <ul>
 *   <li>写入接口<b>不是</b> Dify 协议的一部分，必须用<b>账号密码登录</b>换 JWT。</li>
 *   <li>JWT 默认 30 分钟过期，且过期后表现为"操作静默失败"，因此这里做了
 *       主动续期（提前 2 分钟作废）+ 收到 401 时强制重登并重试一次。</li>
 * </ul>
 * 契约来自 MimirQ 源码（读源码得到，不是猜的）：
 * <pre>
 *   POST /api/v1/auth/login           {identifier,password} → {user, token:{access_token, expires_in}}
 *   GET  /api/v1/datasets/?page&amp;page_size → {total, items:[{id,name,...}]}
 *   POST /api/v1/datasets/            {name,description} → {id,...}
 *   GET  /api/v1/documents/?dataset_id&amp;limit → {total, items:[{id,filename,status,...}]}
 *   POST /api/v1/documents/manual     {dataset_id,filename,file_type,file_size,chunks:[{content,page_number}]}
 *   DELETE /api/v1/documents/{id}     → 204（同时清掉向量）
 * </pre>
 * 约定：dataset 名 = {@code course-{courseId}}，与检索侧的 {@code knowledge-id-pattern} 对齐，
 * 所以"检索不到"和"没灌进去"用的是同一把钥匙，不需要额外维护映射表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MimirQIngestClient {

    private final MimirQProperties properties;
    private final ObjectMapper objectMapper;

    /** 写入接口是重活（要登录、要建库），失败不能拖垮资料保存，因此超时给得比检索宽松。 */
    private static final int WRITE_CONNECT_TIMEOUT_MS = 2000;
    private static final int WRITE_READ_TIMEOUT_MS = 20000;

    /** token 提前作废的余量：留出一次重试的时间，避免"刚好在过期瞬间发请求"。 */
    private static final long TOKEN_EARLY_EXPIRY_MILLIS = 120_000L;

    private volatile RestClient client;
    private volatile String token;
    private volatile long tokenExpiresAtMillis = 0L;

    /** courseId → dataset UUID。写入是低频操作，缓存只为省掉每次上传都 list 一遍 dataset。 */
    private final Map<Long, String> datasetCache = new ConcurrentHashMap<>();

    public static class MimirQIngestException extends RuntimeException {
        public MimirQIngestException(String message) {
            super(message);
        }

        public MimirQIngestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 配置是否完整（开关 + 账号密码）。不完整时调用方应直接跳过，不要发请求。 */
    public boolean isConfigured() {
        return properties.isIngestEnabled()
                && properties.getUsername() != null && !properties.getUsername().isBlank()
                && properties.getPassword() != null && !properties.getPassword().isBlank();
    }

    // ------------------------------------------------------------------ 对外操作

    /**
     * 用最新内容覆盖某条资料在 MimirQ 中的文档。
     * <p>
     * MimirQ 没有"更新文档内容"的接口（manual 文档的正文来自上传时的 chunks），
     * 所以语义是 <b>先删后建</b>。副作用是 chunk_id 会变，这不影响引用（引用基于 materialId + 序号）。
     *
     * @param courseId 用于定位 dataset（course-{courseId}）
     * @param chunks   已提交到 rag_chunk 的切块正文，按 chunk_index 升序
     */
    public void upsertMaterial(Long materialId, Long courseId, String title, List<String> chunks) {
        if (!isConfigured()) {
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            // 没有正文（例如 DOCUMENT/VIDEO 类占位资料）：不该在知识库里留垃圾
            deleteMaterial(materialId, courseId);
            return;
        }
        String datasetId = resolveDatasetId(courseId);
        String fileName = MimirQDocumentNaming.fileName(materialId, title);

        // 先删旧文档，再建新的。这里要删**两类**：
        // ① 同名（m{materialId}__ 前缀）的：处理"改了标题""重复上传"造成的残留；
        // ② 历史命名的（文件名去掉扩展名正好等于资料标题）：早期手工灌库用的是裸标题，
        //    不带上这个，重推之后同一份资料会在知识库里存在两份（旧的一份 + 新的一份），
        //    既浪费索引又让引用映射出现歧义 —— 这是实际重推时暴露出来的问题。
        List<String> stale = new ArrayList<>(findDocumentIds(datasetId,
                MimirQDocumentNaming.idPrefix(materialId)));
        for (String legacy : findDocumentIdsByStem(datasetId, title)) {
            if (!stale.contains(legacy)) {
                stale.add(legacy);
            }
        }
        for (String oldId : stale) {
            try {
                deleteDocument(oldId);
                log.info("MimirQ 旧文档已删除：materialId={}, documentId={}", materialId, oldId);
            } catch (Exception e) {
                log.warn("MimirQ 旧文档删除失败（继续重建）：materialId={}, documentId={}, err={}",
                        materialId, oldId, e.toString());
            }
        }

        int totalChars = chunks.stream().mapToInt(c -> c == null ? 0 : c.length()).sum();

        List<Map<String, Object>> payloadChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("content", chunks.get(i));
            chunk.put("page_number", i + 1);
            payloadChunks.add(chunk);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset_id", datasetId);
        payload.put("filename", fileName);
        payload.put("file_type", MimirQDocumentNaming.FILE_TYPE);
        payload.put("file_size", totalChars);
        payload.put("chunks", payloadChunks);
        // document 级 metadata 仅用于 MimirQ 侧可读性；检索响应不会回传它（见 MimirQDocumentNaming 注释）
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("origin", "trainer-eval");
        metadata.put("material_id", materialId);
        metadata.put("course_id", courseId);
        payload.put("metadata", metadata);

        exchange("POST", properties.getDocumentsPath() + "/manual", payload);
        log.info("MimirQ 文档已写入：materialId={}, dataset={}, file={}, chunks={}",
                materialId, datasetId, fileName, chunks.size());
    }

    /** 删除某条资料在 MimirQ 中的文档。资料被删或正文被清空时调用。 */
    public void deleteMaterial(Long materialId, Long courseId) {
        if (!isConfigured()) {
            return;
        }
        String datasetId = resolveDatasetId(courseId);
        List<String> ids = findDocumentIds(datasetId, MimirQDocumentNaming.idPrefix(materialId));
        for (String id : ids) {
            deleteDocument(id);
            log.info("MimirQ 文档已删除：materialId={}, documentId={}", materialId, id);
        }
    }

    // ------------------------------------------------------------------ dataset / document

    /**
     * 列出 MimirQ 侧的全部知识库及其文档/切块规模，用于运维巡检。
     * 返回的是<b>服务端事实</b>（不是本地期望值），与本地期望对比即可发现漏灌的课程。
     */
    public List<Map<String, Object>> listDatasets() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!isConfigured()) {
            return result;
        }
        JsonNode root = getJson(properties.getDatasetsPath() + "/?page=1&page_size=200");
        for (JsonNode item : root.path("items")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", item.path("name").asText(""));
            row.put("datasetId", item.path("id").asText(""));
            JsonNode summary = item.path("ingestion_summary");
            row.put("documents", summary.path("total_documents").asInt(0));
            row.put("chunks", summary.path("total_chunks").asInt(0));
            row.put("status", item.path("operational_status").asText(""));
            result.add(row);
        }
        return result;
    }

    /** 按名字 {@code course-{courseId}} 找 dataset，找不到就建一个。 */
    private String resolveDatasetId(Long courseId) {
        if (courseId == null) {
            throw new MimirQIngestException("无法确定课程，拒绝写入 MimirQ：courseId 为空");
        }
        String cached = datasetCache.get(courseId);
        if (cached != null) {
            return cached;
        }
        String name = properties.knowledgeIdFor(courseId);

        JsonNode root = getJson(properties.getDatasetsPath() + "/?page=1&page_size=200");
        for (JsonNode item : root.path("items")) {
            if (name.equals(item.path("name").asText(""))) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    datasetCache.put(courseId, id);
                    return id;
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "trainer-eval 同步：课程 " + courseId + " 的培训资料");
        JsonNode created = exchange("POST", properties.getDatasetsPath() + "/", body);
        String id = created.path("id").asText("");
        if (id.isBlank()) {
            throw new MimirQIngestException("MimirQ 建库成功但未返回 id: " + created);
        }
        datasetCache.put(courseId, id);
        log.info("MimirQ 知识库已创建：courseId={}, name={}, datasetId={}", courseId, name, id);
        return id;
    }

    /** 列出 dataset 下文件名以 prefix 开头的文档 id。 */
    private List<String> findDocumentIds(String datasetId, String prefix) {
        return findDocumentIds(datasetId, filename -> filename.startsWith(prefix));
    }

    /**
     * 找出"历史命名"的文档：文件名去掉扩展名后正好等于资料标题。
     * <p>
     * 只用于<b>清理迁移残留</b>。它依赖标题唯一，所以仅当同一课程下不会出现同名资料时才是安全的 ——
     * 这也是为什么新文档一律带上 {@code m{id}__} 前缀：让新数据不依赖这个脆弱的假设。
     */
    private List<String> findDocumentIdsByStem(String datasetId, String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String stem = title.trim();
        return findDocumentIds(datasetId, filename -> {
            String noExt = filename.trim().replaceAll("\\.[A-Za-z0-9]{1,6}$", "").trim();
            return noExt.equals(stem);
        });
    }

    private List<String> findDocumentIds(String datasetId, java.util.function.Predicate<String> matches) {
        JsonNode root = getJson(properties.getDocumentsPath()
                + "/?dataset_id=" + datasetId + "&limit=200");
        List<String> ids = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String filename = item.path("filename").asText("");
            if (!filename.isBlank() && matches.test(filename)) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private void deleteDocument(String documentId) {
        exchange("DELETE", properties.getDocumentsPath() + "/" + documentId, null);
    }

    // ------------------------------------------------------------------ HTTP + 鉴权

    private RestClient client() {
        RestClient existing = this.client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.client == null) {
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(WRITE_CONNECT_TIMEOUT_MS);
                factory.setReadTimeout(WRITE_READ_TIMEOUT_MS);
                this.client = RestClient.builder()
                        .baseUrl(properties.getBaseUrl())
                        .requestFactory(factory)
                        .build();
            }
            return this.client;
        }
    }

    private JsonNode getJson(String path) {
        String raw = execute("GET", path, null);
        return parse(raw, path);
    }

    private JsonNode exchange(String method, String path, Object body) {
        String raw = execute(method, path, body);
        return parse(raw, path);
    }

    /**
     * 执行一次带鉴权的请求。401 时强制重新登录并重试一次 ——
     * MimirQ 的 token 只有 30 分钟，主动续期之外再兜一层，避免长时间运行后写入静默失败。
     */
    private String execute(String method, String path, Object body) {
        try {
            return doExecute(method, path, body, currentToken());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.info("MimirQ 写入返回 401，重新登录后重试一次");
                invalidateToken();
                return doExecute(method, path, body, currentToken());
            }
            throw new MimirQIngestException("MimirQ 写入失败 " + method + " " + path
                    + " → " + e.getStatusCode() + " " + abbreviate(e.getResponseBodyAsString()), e);
        } catch (MimirQIngestException e) {
            throw e;
        } catch (Exception e) {
            throw new MimirQIngestException("MimirQ 写入失败 " + method + " " + path
                    + " → " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    private String doExecute(String method, String path, Object body, String bearer) {
        return client().method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (bearer != null && !bearer.isBlank()) {
                        headers.setBearerAuth(bearer);
                    }
                })
                .body(body == null ? new LinkedHashMap<>() : body)
                .retrieve()
                .body(String.class);
    }

    private JsonNode parse(String raw, String path) {
        if (raw == null || raw.isBlank()) {
            // DELETE 返回 204 无响应体，这是正常路径
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new MimirQIngestException("解析 MimirQ 响应失败 " + path + "：" + abbreviate(raw), e);
        }
    }

    private String currentToken() {
        String existing = this.token;
        if (existing != null && System.currentTimeMillis() < tokenExpiresAtMillis) {
            return existing;
        }
        synchronized (this) {
            if (this.token != null && System.currentTimeMillis() < tokenExpiresAtMillis) {
                return this.token;
            }
            login();
            return this.token;
        }
    }

    private void invalidateToken() {
        this.token = null;
        this.tokenExpiresAtMillis = 0L;
    }

    private void login() {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("identifier", properties.getUsername());
        credentials.put("password", properties.getPassword());

        String raw;
        try {
            raw = client().post()
                    .uri(properties.getLoginPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credentials)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new MimirQIngestException("MimirQ 登录失败（检查 mimirq.username/password）："
                    + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }

        JsonNode root = parse(raw, properties.getLoginPath());
        String accessToken = root.path("token").path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new MimirQIngestException("MimirQ 登录响应缺少 token.access_token：" + abbreviate(raw));
        }
        long expiresInSec = root.path("token").path("expires_in").asLong(1800L);
        this.token = accessToken;
        this.tokenExpiresAtMillis = System.currentTimeMillis()
                + Math.max(expiresInSec * 1000L - TOKEN_EARLY_EXPIRY_MILLIS, 30_000L);
        log.info("MimirQ 写入登录成功：identifier={}, expires_in={}s", properties.getUsername(), expiresInSec);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
