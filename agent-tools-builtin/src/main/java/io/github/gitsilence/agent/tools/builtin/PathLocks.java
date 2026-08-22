package io.github.gitsilence.agent.tools.builtin;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PathLocks {

    private final ConcurrentMap<String, Object> locks =
        new ConcurrentHashMap<String, Object>();

    Object forPath(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        Object created = new Object();
        Object existing = locks.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }
}
