#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一个最小可用的 MCP（Model Context Protocol）stdio 服务端 —— 只依赖 Python 标准库。

它的用途不是"功能"，而是**证明接入链路真的通**：
    trainer-eval(Spring Boot)  →  StdioMcpClient  →  子进程(本文件)  →  JSON-RPC over stdio

因此它刻意实现了协议里最小但完整的一组方法：
    initialize               握手
    notifications/initialized 通知（无响应）
    tools/list               工具发现
    tools/call               工具调用

传输格式（MCP 规范）：**一行一个 JSON**，UTF-8，不带 Content-Length 头。
任何 "JSON-RPC 2.0" 的实现细节都在下面用注释标出来了，方便对照规范阅读。

暴露的工具（故意一个"有外部数据"、一个"纯计算"，覆盖两类典型 MCP 场景）：
    policy_search      查企业内部培训制度条款（模拟外部制度库）
    training_hours     按参训人数与课时计算总人时

手工试一下（不用起整个 Java 应用）：
    echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | python demo_mcp_server.py
"""

import json
import sys

PROTOCOL_VERSION = "2024-11-05"

# ---------------------------------------------------------------------------
# 模拟的"外部知识源"：企业培训制度条款
# 放在这里是为了让 demo 有真实可查的数据，而不是只回显
# ---------------------------------------------------------------------------
POLICIES = [
    {
        "code": "TR-001",
        "title": "新员工入职培训",
        "content": "新员工须在入职 30 日内完成不少于 16 学时的入职培训，其中安全生产必修不少于 4 学时。",
    },
    {
        "code": "TR-002",
        "title": "年度学时要求",
        "content": "全体在岗员工每自然年度须完成不少于 40 学时的培训，内训师不少于 60 学时。",
    },
    {
        "code": "TR-003",
        "title": "考核与补考",
        "content": "培训考核成绩低于 60 分者须在 15 个工作日内完成一次补考；补考仍不合格的，由所属部门安排补训。",
    },
    {
        "code": "TR-004",
        "title": "培训费用报销",
        "content": "外部培训单次费用超过 2000 元需经部门负责人与人力资源部双重审批；培训后须提交学习总结并可被要求内部转训。",
    },
    {
        "code": "TR-005",
        "title": "内训师激励",
        "content": "内部讲师按授课学时计入年度考核，每课时补贴 100 元，年度累计不超过 5000 元。",
    },
]


def tool_policy_search(keyword: str) -> str:
    """按关键词检索制度条款。空关键词返回全部。"""
    keyword = (keyword or "").strip()
    if not keyword:
        hits = POLICIES
    else:
        hits = [p for p in POLICIES if keyword in p["title"] or keyword in p["content"] or keyword in p["code"]]
    if not hits:
        return f"制度库中未找到与「{keyword}」相关的条款。"
    lines = [f"命中 {len(hits)} 条制度条款："]
    for p in hits:
        lines.append(f"[{p['code']}] {p['title']}：{p['content']}")
    return "\n".join(lines)


def tool_training_hours(headcount: int, hours: float) -> str:
    """计算总人时，并顺带给出是否符合年度学时要求的粗判。"""
    try:
        headcount = int(headcount)
        hours = float(hours)
    except (TypeError, ValueError):
        return "参数错误：headcount 需为整数，hours 需为数字。"
    if headcount <= 0 or hours <= 0:
        return "参数错误：headcount 与 hours 都必须大于 0。"
    total = headcount * hours
    verdict = "达到" if hours >= 40 else "未达到"
    return (f"{headcount} 人 × {hours} 学时 = {total:.1f} 人时。"
            f"按人均口径，{verdict}年度 40 学时的基本要求。")


TOOLS = [
    {
        "name": "policy_search",
        "description": "检索企业内部培训管理制度的条款（含学时要求、考核补考、费用报销、内训师激励）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "keyword": {
                    "type": "string",
                    "description": "检索关键词，如：补考、学时、报销。留空则返回全部条款。",
                }
            },
            "required": [],
        },
    },
    {
        "name": "training_hours",
        "description": "按参训人数与课时计算总人时，并判断人均学时是否达到年度要求。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "headcount": {"type": "integer", "description": "参训人数"},
                "hours": {"type": "number", "description": "每人课时数"},
            },
            "required": ["headcount", "hours"],
        },
    },
]


def handle_tool_call(name: str, arguments: dict) -> tuple[str, bool]:
    """返回 (文本结果, 是否出错)。"""
    arguments = arguments or {}
    if name == "policy_search":
        return tool_policy_search(arguments.get("keyword", "")), False
    if name == "training_hours":
        text = tool_training_hours(arguments.get("headcount"), arguments.get("hours"))
        return text, text.startswith("参数错误")
    return f"未知工具：{name}", True


def respond(message: dict) -> None:
    """把响应写成一行 JSON 并立刻 flush —— 不 flush 的话客户端会一直等。"""
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> None:
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            # 解析不了的行按规范应当被忽略，而不是让服务退出
            continue

        method = request.get("method")
        request_id = request.get("id")

        # 通知：没有 id，按规范不回复
        if request_id is None:
            continue

        if method == "initialize":
            respond({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "trainer-eval-demo-mcp", "version": "1.0.0"},
                },
            })
        elif method == "tools/list":
            respond({"jsonrpc": "2.0", "id": request_id, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = request.get("params") or {}
            text, is_error = handle_tool_call(params.get("name"), params.get("arguments"))
            # 工具执行失败不是协议错误：回 result + isError=true，让调用方（模型）看到原因
            respond({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {"content": [{"type": "text", "text": text}], "isError": is_error},
            })
        elif method == "ping":
            respond({"jsonrpc": "2.0", "id": request_id, "result": {}})
        else:
            respond({
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"},
            })


if __name__ == "__main__":
    main()
