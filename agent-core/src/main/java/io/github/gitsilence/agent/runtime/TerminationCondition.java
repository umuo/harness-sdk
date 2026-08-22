package io.github.gitsilence.agent.runtime;

import io.github.gitsilence.agent.state.AgentStateSnapshot;

import java.util.Optional;

public interface TerminationCondition {

    Optional<StopSignal> evaluate(AgentStateSnapshot state);
}
