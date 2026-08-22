# Multi-Agent Composition

Multi-Agent features reuse Agent, Agent-as-Tool, isolated State and
`CompletableFuture`. There is no separate orchestration runtime.

## Supervisor

A Supervisor is an ordinary Agent with specialist Agents registered as Tools:

```java
Agent supervisor = Agent.builder()
    .name("supervisor")
    .description("Delegates work to specialists")
    .instructions("Delegate research and coding to the appropriate tools.")
    .model(model)
    .tool(researchAgent)
    .tool(codingAgent)
    .tool(reviewAgent)
    .build();
```

Each specialist invocation creates a fresh AgentState. The parent sends a
`task` argument and receives only the child result plus child Turn metadata.

## Independent parallel Agents

```java
List<AgentResult> results = AgentExecutions.runParallel(Arrays.asList(
    AgentInvocation.of(researchAgent, "Investigate option A"),
    AgentInvocation.of(researchAgent, "Investigate option B"),
    AgentInvocation.of(reviewAgent, "Define review criteria")
)).join();
```

Calls start immediately, results remain in input order, a failure cancels
unfinished calls, and cancellation of the aggregate future propagates to every
call. No mutable State is shared.

## Patterns that need no new runtime

- Router: an Agent chooses among registered Agent Tools.
- Review/debate: call independent Agents, then pass their outputs to a reviewer.
- Parallel research: use `AgentExecutions.runParallel`, then synthesize results
  with another Agent.
- Supervisor: register specialists with `.tool(agent)`.

## Deferred patterns

Handoff and Swarm require an explicit transfer-of-control result and an
AgentRegistry. They should be added as small primitives rather than encoded as
ordinary tool text. Durable checkpoints, distributed scheduling and a graph DSL
remain outside the SDK scope.
