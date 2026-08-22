export const LOCALE_COOKIE = "agent-observatory-locale";

export type Locale = "zh-CN" | "en";

const zh = {
  brandSubtitle: "Agent 运行时信号",
  ingestionOnline: "接收服务在线",
  language: "语言",
  refresh: {
    automatic: "每 5 秒自动刷新",
    button: "刷新",
  },
  status: {
    COMPLETED: "已完成",
    FAILED: "失败",
    STOPPED: "已停止",
    CANCELLED: "已取消",
    RUNNING: "运行中",
    OK: "成功",
    ERROR: "错误",
  },
  kind: {
    TURN: "任务轮次",
    STEP: "执行步骤",
    MODEL: "模型调用",
    TOOL: "工具调用",
  },
  dashboard: {
    overview: "运行概览",
    title: "每一轮执行，清晰可见。",
    description:
      "跟踪整个 Agent 层级中的模型调用、工具执行、延迟、错误和 Token 使用情况。",
    summaryLabel: "Trace 汇总",
    metrics: {
      turns: "任务轮次",
      retained: "当前保留",
      successRate: "成功率",
      nonCompleted: "未完成",
      p95Latency: "P95 延迟",
      turnDuration: "轮次耗时",
      tokens: "Token 用量",
      inputOutput: "输入 + 输出",
      toolErrors: "工具错误",
      calls: "次调用",
    },
    recent: "最近活动",
    turnsTitle: "Agent 任务轮次",
    filters: {
      agentLabel: "按 Agent 筛选",
      agentPlaceholder: "Agent 名称",
      statusLabel: "按状态筛选",
      allStatuses: "全部状态",
      apply: "应用",
      clear: "清除",
    },
    table: {
      agent: "Agent",
      status: "状态",
      started: "开始时间",
      latency: "耗时",
      steps: "步骤",
      modelTool: "模型 / 工具",
      tokens: "Token",
      error: "个错误",
      inspect: "查看 →",
    },
    empty: {
      noMatch: "没有符合筛选条件的任务轮次",
      waiting: "正在等待第一个任务轮次",
      adjust: "调整 Agent 名称或状态筛选条件以查看更多 Trace。",
      connect:
        "将 Java SDK 的平台 Exporter 指向此服务，即可开始接收 Trace。",
    },
  },
  detail: {
    turns: "任务轮次",
    agentTurn: "Agent 任务轮次",
    totalDuration: "总耗时",
    turnError: "轮次错误",
    stats: {
      steps: "执行步骤",
      modelCalls: "模型调用",
      toolCalls: "工具调用",
      toolErrors: "工具错误",
      inputTokens: "输入 Token",
      outputTokens: "输出 Token",
    },
    context: "上下文",
    traceMetadata: "Trace 元数据",
    traceId: "Trace ID",
    parentTurn: "父任务轮次",
    rootTurn: "根任务轮次",
    parentSpan: "父 Span",
    streamEvents: "流式事件",
    noResourceAttributes: "没有资源属性",
    timeline: "执行时间线",
    timelineTitle: "任务轮次 → 步骤 → 模型 / 工具",
    spanId: "Span ID",
    parent: "父 Span",
    status: "状态",
    error: "错误",
    noCapturedAttributes: "没有捕获的属性",
  },
  notFound: {
    title: "未找到任务轮次",
    description: "该 Trace 可能已超过配置的保留期限。",
    back: "返回任务轮次",
  },
} as const;

const en = {
  brandSubtitle: "Harness runtime signals",
  ingestionOnline: "Ingestion online",
  language: "Language",
  refresh: {
    automatic: "Auto refresh every 5 seconds",
    button: "Refresh",
  },
  status: {
    COMPLETED: "Completed",
    FAILED: "Failed",
    STOPPED: "Stopped",
    CANCELLED: "Cancelled",
    RUNNING: "Running",
    OK: "OK",
    ERROR: "Error",
  },
  kind: {
    TURN: "Turn",
    STEP: "Step",
    MODEL: "Model",
    TOOL: "Tool",
  },
  dashboard: {
    overview: "Runtime overview",
    title: "Every turn, visible.",
    description:
      "Follow model calls, tool execution, latency, errors, and token use across your Agent hierarchy.",
    summaryLabel: "Trace summary",
    metrics: {
      turns: "Turns",
      retained: "retained",
      successRate: "Success rate",
      nonCompleted: "non-completed",
      p95Latency: "P95 latency",
      turnDuration: "turn duration",
      tokens: "Tokens",
      inputOutput: "input + output",
      toolErrors: "Tool errors",
      calls: "calls",
    },
    recent: "Recent activity",
    turnsTitle: "Agent turns",
    filters: {
      agentLabel: "Filter by agent",
      agentPlaceholder: "Agent name",
      statusLabel: "Filter by status",
      allStatuses: "All statuses",
      apply: "Apply",
      clear: "Clear",
    },
    table: {
      agent: "Agent",
      status: "Status",
      started: "Started",
      latency: "Latency",
      steps: "Steps",
      modelTool: "Model / Tool",
      tokens: "Tokens",
      error: "error",
      inspect: "Inspect →",
    },
    empty: {
      noMatch: "No turns match these filters",
      waiting: "Waiting for the first turn",
      adjust: "Adjust the agent or status filter to reveal more traces.",
      connect:
        "Point the Java SDK platform exporter at this service to begin ingesting traces.",
    },
  },
  detail: {
    turns: "Turns",
    agentTurn: "Agent turn",
    totalDuration: "Total duration",
    turnError: "Turn error",
    stats: {
      steps: "Steps",
      modelCalls: "Model calls",
      toolCalls: "Tool calls",
      toolErrors: "Tool errors",
      inputTokens: "Input tokens",
      outputTokens: "Output tokens",
    },
    context: "Context",
    traceMetadata: "Trace metadata",
    traceId: "Trace ID",
    parentTurn: "Parent turn",
    rootTurn: "Root turn",
    parentSpan: "Parent span",
    streamEvents: "Stream events",
    noResourceAttributes: "No resource attributes",
    timeline: "Execution timeline",
    timelineTitle: "Turn → step → model / tool",
    spanId: "Span ID",
    parent: "Parent",
    status: "Status",
    error: "Error",
    noCapturedAttributes: "No captured attributes",
  },
  notFound: {
    title: "Turn not found",
    description: "The trace may have expired under the configured retention limit.",
    back: "Back to turns",
  },
} as const;

const dictionaries = { "zh-CN": zh, en } as const;

export function dictionary(locale: Locale) {
  return dictionaries[locale];
}

export function statusLabel(status: string, locale: Locale): string {
  const labels = dictionaries[locale].status as Record<string, string>;
  return labels[status.toUpperCase()] ?? status;
}

export function kindLabel(kind: string, locale: Locale): string {
  const labels = dictionaries[locale].kind as Record<string, string>;
  return labels[kind.toUpperCase()] ?? kind;
}
