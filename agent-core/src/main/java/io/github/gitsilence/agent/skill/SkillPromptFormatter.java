package io.github.gitsilence.agent.skill;

/** Formats lightweight Skill discovery metadata for the Agent system prompt. */
public final class SkillPromptFormatter {

    private SkillPromptFormatter() {
    }

    public static String format(SkillRegistry registry) {
        if (registry == null) throw new NullPointerException("registry");
        if (registry.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder()
            .append("## Available Agent Skills\n\n")
            .append("Skills provide optional specialized instructions. When a task ")
            .append("matches a Skill description, call skill_load with its name before ")
            .append("following that Skill. Load referenced resources only when needed.\n\n")
            .append("<available_skills>\n");
        for (Skill skill : registry.definitions()) {
            prompt.append("  <skill>\n")
                .append("    <name>").append(escape(skill.getName())).append("</name>\n")
                .append("    <description>")
                .append(escape(skill.getDescription()))
                .append("</description>\n")
                .append("    <location>")
                .append(escape(skill.getSkillFile().toString()))
                .append("</location>\n")
                .append("  </skill>\n");
        }
        return prompt.append("</available_skills>").toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
