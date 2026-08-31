package io.github.gitsilence.agent.tool;

/** 同一模型响应中多个 Tool Call 的调度模式。 */
public enum ToolExecutionMode {
    /** 严格按照模型给出的顺序逐个执行。 */
    SEQUENTIAL,
    /** 仅并发执行显式声明并行安全的 Tool，其他 Tool 作为独占屏障。 */
    PARALLEL
}
