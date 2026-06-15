package com.lianyu.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在 Spring 启动前加载仓库根目录 .env，解决 IDE 直接 Run 时工作目录不一致导致读不到环境变量的问题。
 * Docker Compose 仍通过 env_file 注入，此处仅补缺未设置的键。
 */
final class DotEnvBootstrap {

    private DotEnvBootstrap() {
    }

    static void load() {
        Path loaded = null;
        for (Path candidate : resolveCandidates()) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            applyEnvFile(candidate);
            loaded = candidate;
            break;
        }
        if (loaded != null) {
            System.setProperty("lianyu.env.loaded", loaded.toString());
        }
    }

    private static List<Path> resolveCandidates() {
        Set<String> seen = new LinkedHashSet<>();
        List<Path> candidates = new ArrayList<>();

        String explicit = System.getenv("LIANYU_ENV_FILE");
        if (explicit != null && !explicit.isBlank()) {
            addCandidate(candidates, seen, Path.of(explicit).toAbsolutePath().normalize());
        }

        String projectRoot = System.getenv("LIANYU_PROJECT_ROOT");
        if (projectRoot != null && !projectRoot.isBlank()) {
            addCandidate(candidates, seen, Path.of(projectRoot).toAbsolutePath().normalize().resolve(".env"));
        }

        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth <= 4; depth++) {
            Path base = cwd;
            for (int i = 0; i < depth; i++) {
                base = base.getParent();
                if (base == null) {
                    break;
                }
            }
            if (base != null) {
                addCandidate(candidates, seen, base.resolve(".env"));
            }
        }
        return candidates;
    }

    private static void addCandidate(List<Path> candidates, Set<String> seen, Path path) {
        String key = path.toString();
        if (seen.add(key)) {
            candidates.add(path);
        }
    }

    private static void applyEnvFile(Path path) {
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (key.isEmpty()) {
                    continue;
                }
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (IOException ignored) {
            // optional bootstrap
        }
    }
}
