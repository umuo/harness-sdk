package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.MessageRole;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.skill.Skill;
import io.github.gitsilence.agent.skill.SkillLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {

    @TempDir
    Path temporary;

    @Test
    void parsesStandardFrontmatterAndDiscoversWithDiagnostics() throws Exception {
        Path valid = createSkill(
            "code-review",
            "description: >-\n"
                + "  Reviews Java code and explains when changes are needed.\n"
                + "license: Apache-2.0\n"
                + "compatibility: Requires a Java project\n"
                + "allowed-tools: read_file edit\n"
                + "metadata:\n"
                + "  author: \"example\"\n"
                + "  version: \"1\"\n",
            "# Review workflow\n\nInspect the smallest relevant surface.\n"
        );
        Path invalid = Files.createDirectories(temporary.resolve("wrong-directory"));
        write(invalid.resolve("SKILL.md"),
            "---\nname: mismatched-name\ndescription: Invalid example\n---\nBody\n");

        Skill skill = SkillLoader.load(valid);
        SkillLoader.Discovery discovery = SkillLoader.discover(temporary);

        assertEquals("code-review", skill.getName());
        assertTrue(skill.getDescription().startsWith("Reviews Java code"));
        assertEquals("Apache-2.0", skill.getLicense());
        assertEquals("example", skill.getMetadata().get("author"));
        assertEquals(2, skill.getAllowedTools().size());
        assertEquals(1, discovery.getSkills().size());
        assertEquals(1, discovery.getDiagnostics().size());
        assertTrue(discovery.getDiagnostics().get(0).getMessage().contains(
            "must match directory"
        ));
    }

    @Test
    void exposesOnlyMetadataUntilModelLoadsSkill() throws Exception {
        Path directory = createSkill(
            "code-review",
            "description: Reviews Java code when a review is requested.\n",
            "# Private workflow\n\nPRIVATE-INSTRUCTION: inspect concurrency first.\n"
        );
        Skill skill = SkillLoader.load(directory);
        RecordingModel model = new RecordingModel(
            tool("skill-call", "skill_load", "{\"name\":\"code-review\"}"),
            finalAnswer("reviewed")
        );
        Agent agent = Agent.builder()
            .name("reviewer")
            .description("Reviews code")
            .instructions("Answer carefully.")
            .model(model)
            .skill(skill)
            .build();

        AgentResult result = agent.run("review this change");

        String initialSystem = model.requests.get(0).getMessages().get(0).getContent();
        assertTrue(initialSystem.contains("<available_skills>"));
        assertTrue(initialSystem.contains("Reviews Java code"));
        assertFalse(initialSystem.contains("PRIVATE-INSTRUCTION"));
        assertEquals("skill_load", model.requests.get(0).getTools().get(0).getName());

        ChatMessage loaded = model.requests.get(1).getMessages().get(3);
        assertEquals(MessageRole.TOOL, loaded.getRole());
        assertTrue(loaded.getContent().contains("PRIVATE-INSTRUCTION"));
        assertFalse(loaded.getContent().contains("description: Reviews Java code"));
        assertEquals("reviewed", result.getOutput());
        assertEquals(1, agent.getSkillRegistry().definitions().size());
    }

    @Test
    void loadsReferencedTextWithinRootAndRejectsTraversal() throws Exception {
        Path directory = createSkill(
            "code-review",
            "description: Reviews Java code.\n",
            "Read references/checklist.md when detailed checks are needed.\n"
        );
        Path references = Files.createDirectories(directory.resolve("references"));
        write(references.resolve("checklist.md"), "CHECK IMMUTABILITY\n");

        RecordingModel reader = new RecordingModel(
            tool(
                "skill-call", "skill_load",
                "{\"name\":\"code-review\","
                    + "\"resource\":\"references/checklist.md\"}"
            ),
            finalAnswer("done")
        );
        Agent readingAgent = Agent.builder()
            .name("reader")
            .description("Reads a Skill resource")
            .model(reader)
            .skill(directory)
            .build();

        AgentResult read = readingAgent.run("load checklist");

        assertFalse(read.getState().getToolResults().get(0).getResult().isError());
        assertTrue(read.getState().getToolResults().get(0).getResult()
            .getContent().contains("CHECK IMMUTABILITY"));

        RecordingModel traversal = new RecordingModel(
            tool(
                "skill-call", "skill_load",
                "{\"name\":\"code-review\",\"resource\":\"../secret.txt\"}"
            ),
            finalAnswer("recovered")
        );
        Agent guardedAgent = Agent.builder()
            .name("guarded")
            .description("Rejects unsafe Skill paths")
            .model(traversal)
            .skill(directory)
            .build();

        AgentResult guarded = guardedAgent.run("escape the Skill root");

        assertEquals(
            "SKILL_RESOURCE_OUTSIDE_ROOT",
            guarded.getState().getToolResults().get(0).getResult()
                .getErrorInfo().getCode()
        );
    }

    @Test
    void strictBuilderDiscoveryRejectsInvalidSkills() throws Exception {
        Path invalid = Files.createDirectories(temporary.resolve("invalid"));
        write(invalid.resolve("SKILL.md"),
            "---\nname: invalid\n---\nMissing description\n");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> Agent.builder().skillsFrom(temporary)
        );

        assertTrue(error.getMessage().contains("description"));
    }

    private Path createSkill(String name, String frontmatter, String body)
            throws Exception {
        Path directory = Files.createDirectories(temporary.resolve(name));
        write(directory.resolve("SKILL.md"),
            "---\nname: " + name + "\n" + frontmatter + "---\n" + body);
        return directory;
    }

    private static void write(Path path, String content) throws Exception {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static ModelResponse tool(String id, String name, String arguments) {
        return ModelResponse.of(ChatMessage.assistant(
            null,
            Collections.singletonList(new ToolCall(id, name, arguments))
        ));
    }

    private static ModelResponse finalAnswer(String content) {
        return ModelResponse.of(ChatMessage.assistant(content));
    }

    private static final class RecordingModel implements ChatModel {
        private final List<ModelResponse> responses;
        private final List<ModelRequest> requests = new ArrayList<ModelRequest>();
        private int index;

        private RecordingModel(ModelResponse... responses) {
            this.responses = java.util.Arrays.asList(responses);
        }

        @Override
        public synchronized CompletableFuture<ModelResponse> generate(
                ModelRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(responses.get(index++));
        }
    }
}
