package io.github.gitsilence.agent.skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, deterministic registry of file-backed Agent Skills. */
public final class SkillRegistry {

    private final Map<String, Skill> skills;
    private final Collection<Skill> definitions;

    private SkillRegistry(Map<String, Skill> skills) {
        this.skills = Collections.unmodifiableMap(
            new LinkedHashMap<String, Skill>(skills)
        );
        this.definitions = Collections.unmodifiableCollection(
            new ArrayList<Skill>(this.skills.values())
        );
    }

    public static SkillRegistry of(Collection<? extends Skill> skills) {
        Objects.requireNonNull(skills, "skills");
        Map<String, Skill> registered = new LinkedHashMap<String, Skill>();
        for (Skill skill : skills) {
            if (skill == null) throw new NullPointerException("skill");
            Skill previous = registered.put(skill.getName(), skill);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "Duplicate Skill name '" + skill.getName() + "': "
                        + previous.getSkillFile() + " and " + skill.getSkillFile()
                );
            }
        }
        return new SkillRegistry(registered);
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<Skill> definitions() {
        return definitions;
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }
}
