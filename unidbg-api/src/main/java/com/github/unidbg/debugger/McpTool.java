package com.github.unidbg.debugger;

public interface McpTool {

    String name();

    String description();

    default String[] paramNames() {
        return new String[0];
    }

    void execute(String[] params) throws Exception;

}
