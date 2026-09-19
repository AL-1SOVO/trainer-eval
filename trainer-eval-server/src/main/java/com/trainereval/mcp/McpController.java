package com.trainereval.mcp;

import com.trainereval.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 管理接口。
 *
 * <p><b>为什么这里没有"新增/删除 server"的接口</b>：
 * stdio 传输的本质是"在本机执行一个命令"。如果开放 API 动态添加 server，
 * 等于给系统装了一个远程命令执行入口——任何拿到管理员令牌的人都能在服务器上跑任意程序。
 * 所以 server 列表只从配置文件读，运行时只有四个受限动作：
 * 看概览、看状态、看工具清单、重连、试调。
 *
 * <p>权限：全部要求 MANAGER。内训师即使被 {@code allowed-roles} 放行可用工具，
 * 也不需要看到连接配置与试调入口。
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpToolRegistry registry;

    /** 概览：是否启用、配了几个 server、连上了几个、发现了多少工具。 */
    @GetMapping("/overview")
    @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> overview() {
        return R.ok(registry.overview());
    }

    /** 各 server 的连接状态、角色白名单、以及最近一次失败原因。 */
    @GetMapping("/servers")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<Map<String, Object>>> servers() {
        return R.ok(registry.status());
    }

    /** 已发现的工具清单——即"模型实际能看到什么"，用于核对描述与参数 schema。 */
    @GetMapping("/tools")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<Map<String, Object>>> tools() {
        return R.ok(registry.tools());
    }

    /** 强制重连全部 server。改完配置或修好外部服务后用它，不必重启应用。 */
    @PostMapping("/reload")
    @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> reload() {
        Map<String, Object> result = registry.reload();
        return Boolean.FALSE.equals(result.get("enabled"))
                ? R.error(String.valueOf(result.get("message")))
                : R.ok("MCP 重连完成", result);
    }

    /** 试调：直接调用某个 MCP 工具并把原始结果返回，用于配置后立即验证。 */
    @PostMapping("/test-call")
    @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> testCall(@RequestBody Map<String, Object> body) {
        String server = body.get("server") == null ? "" : String.valueOf(body.get("server"));
        String tool = body.get("tool") == null ? "" : String.valueOf(body.get("tool"));
        if (server.isBlank() || tool.isBlank()) {
            return R.error("server 与 tool 不能为空");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = body.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> result = registry.testCall(server, tool, arguments);
        return Boolean.TRUE.equals(result.get("ok"))
                ? R.ok("调用成功", result)
                : R.error(String.valueOf(result.getOrDefault("message", "调用失败")));
    }
}
