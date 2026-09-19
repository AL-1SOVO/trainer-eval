package com.trainereval.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * stdio 传输的 MCP 客户端：启动一个本地子进程，用"一行一个 JSON"的方式通信。
 *
 * <h3>为什么 stdio 是最常用的一档</h3>
 * 绝大多数 MCP server（官方 filesystem / git / sqlite，以及社区那些 npx 包）都是 stdio 形态，
 * 不需要暴露端口、不需要鉴权，装完即用。
 *
 * <h3>三件必须做对的事</h3>
 * <ol>
 *   <li><b>串行化</b>：stdio 只有一根管道，并发写会把 JSON 交错成垃圾。
 *       这里用 {@code synchronized} 把"写请求 + 读响应"整体串起来。</li>
 *   <li><b>过滤非响应消息</b>：管道上会混进通知（无 id）和服务端日志。
 *       按 id 匹配、跳过解析不了的行走。</li>
 *   <li><b>持续读 stderr</b>：子进程写满 stderr 缓冲区就会卡死（经典陷阱）。
 *       所以起一个守护线程专门读，并保留最近若干行用于排错。</li>
 * </ol>
 *
 * <h3>平台差异</h3>
 * Windows 上 {@code .cmd/.bat} 不能被子进程直接执行，必须经 {@code cmd.exe /c}。
 * npx 装出来的 MCP server 在 Windows 上正是 {@code npx.cmd}，所以这里统一处理，
 * 免得运维在 Windows 上配了个跑不起来的命令还看不出原因。
 */
@Slf4j
public class StdioMcpClient extends AbstractMcpClient {

    private static final int STDERR_KEEP_LINES = 40;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final Object ioLock = new Object();

    public StdioMcpClient(String serverName, McpProperties.Server config, ObjectMapper objectMapper,
                          Duration defaultTimeout) {
        super(serverName, config, objectMapper, defaultTimeout);
    }

    @Override
    protected void open() throws Exception {
        if (process != null && process.isAlive()) {
            return;
        }
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new McpException(serverName, "stdio 传输必须配置 command");
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add(config.getCommand());
        commandLine.addAll(config.getArgs() == null ? List.of() : config.getArgs());
        commandLine = normalizeForPlatform(commandLine);

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.redirectErrorStream(false);
        // 占位符解析：配置里写 ${TAVILY_API_KEY}，从宿主环境取值。
        // 这样密钥只存在于环境变量，不会出现在 application.yml 里。
        Map<String, String> env = builder.environment();
        if (config.getEnv() != null) {
            config.getEnv().forEach((key, value) -> env.put(key, resolvePlaceholders(value)));
        }

        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrain();
        log.info("MCP stdio 子进程已启动：server={}, pid={}, cmd={}",
                serverName, process.pid(), String.join(" ", commandLine));
    }

    /**
     * Windows：{@code .cmd/.bat} 需要 cmd.exe 中转；其余情况原样执行。
     * 放在一个方法里是为了让"为什么多套了一层 shell"这件事有据可查。
     */
    private List<String> normalizeForPlatform(List<String> commandLine) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!windows) {
            return commandLine;
        }
        String exe = commandLine.get(0);
        String lower = exe.toLowerCase();
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("cmd.exe");
            wrapped.add("/c");
            wrapped.addAll(commandLine);
            return wrapped;
        }
        return commandLine;
    }

    private String resolvePlaceholders(String value) {
        if (value == null) {
            return null;
        }
        String resolved = value;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    /** stderr 必须被持续消费，否则子进程可能因为管道写满而卡死。 */
    private void startStderrDrain() {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        while (stderrTail.size() > STDERR_KEEP_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                    log.debug("[mcp:{}] {}", serverName, line);
                }
            } catch (IOException ignored) {
                // 进程结束时读到 EOF/异常都是正常的
            }
        }, "mcp-stderr-" + serverName);
        drain.setDaemon(true);
        drain.start();
    }

    @Override
    protected JsonNode exchange(ObjectNode request, Duration timeout) throws Exception {
        synchronized (ioLock) {
            if (process == null || !process.isAlive()) {
                throw new McpException(serverName, "子进程已退出" + stderrSummary());
            }
            long deadline = System.currentTimeMillis() + Math.max(timeout.toMillis(), 1000);

            stdin.write(objectMapper.writeValueAsString(request));
            stdin.write('\n');
            stdin.flush();

            String expectedId = request.path("id").asText();
            while (System.currentTimeMillis() < deadline) {
                if (!stdout.ready() && !waitForReadable(deadline)) {
                    break;
                }
                String line = stdout.readLine();
                if (line == null) {
                    throw new McpException(serverName, "子进程关闭了 stdout" + stderrSummary());
                }
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = tryParse(line);
                if (node == null) {
                    // 不是 JSON：多半是服务端日志，丢掉继续读
                    continue;
                }
                // 通知（没有 id）直接跳过；只有 id 对得上的才是这次请求的响应
                if (!node.hasNonNull("id") || !expectedId.equals(node.path("id").asText())) {
                    continue;
                }
                return node;
            }
            throw new McpException(serverName, "等待响应超时（" + timeout.toMillis() + "ms）" + stderrSummary());
        }
    }

    /**
     * 等 stdout 有数据可读。{@code BufferedReader.ready()} 对管道不可靠，
     * 所以用"睡眠小步轮询 + 总超时"的方式，避免在 readLine 上无限阻塞。
     */
    private boolean waitForReadable(long deadline) {
        while (System.currentTimeMillis() < deadline) {
            try {
                if (stdout.ready()) {
                    return true;
                }
                if (process != null && !process.isAlive()) {
                    return true;
                }
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    private JsonNode tryParse(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }

    private String stderrSummary() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            List<String> last = new ArrayList<>(stderrTail);
            int from = Math.max(last.size() - 5, 0);
            return "；子进程最近输出：" + String.join(" | ", last.subList(from, last.size()));
        }
    }

    @Override
    protected void send(ObjectNode notification) throws Exception {
        synchronized (ioLock) {
            if (stdin == null) {
                return;
            }
            stdin.write(objectMapper.writeValueAsString(notification));
            stdin.write('\n');
            stdin.flush();
        }
    }

    @Override
    protected void shutdown() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 关不掉也无所谓，下面还有 destroy
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
        stdout = null;
    }

    @Override
    public void close() {
        synchronized (ioLock) {
            shutdown();
        }
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = super.status();
        status.put("pid", process == null || !process.isAlive() ? null : process.pid());
        status.put("alive", process != null && process.isAlive());
        synchronized (stderrTail) {
            if (!stderrTail.isEmpty()) {
                List<String> last = new ArrayList<>(stderrTail);
                int from = Math.max(last.size() - 5, 0);
                status.put("stderrTail", last.subList(from, last.size()));
            }
        }
        return status;
    }
}
