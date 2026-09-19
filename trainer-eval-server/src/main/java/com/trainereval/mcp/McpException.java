package com.trainereval.mcp;

/** MCP 调用失败的统一异常：把"子进程没起来""握手失败""工具报错"都收敛成一种类型。 */
public class McpException extends RuntimeException {

    private final String server;

    public McpException(String server, String message) {
        super(message);
        this.server = server;
    }

    public McpException(String server, String message, Throwable cause) {
        super(message, cause);
        this.server = server;
    }

    public String server() {
        return server;
    }

    @Override
    public String getMessage() {
        return "[" + server + "] " + super.getMessage();
    }
}
