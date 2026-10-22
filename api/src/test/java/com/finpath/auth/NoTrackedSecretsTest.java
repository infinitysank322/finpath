package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T29 acceptance: the Google client secret only ever comes from the environment. Scans every file
 * git would commit (tracked plus untracked-but-not-ignored) for a Google client-secret-shaped
 * string. Ignored files, such as the real .env, are never opened. The pattern is assembled at
 * runtime so this file does not match itself.
 */
class NoTrackedSecretsTest {

    private static final Pattern GOOGLE_CLIENT_SECRET = Pattern.compile("GOCSPX" + "-[A-Za-z0-9_-]{8,}");

    @Test
    void noGoogleClientSecretIsTracked() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "SKIPPED: not inside a git work tree");
        List<String> offenders = new ArrayList<>();
        for (String file : gitFiles(root)) {
            Path path = root.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String text = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
            if (GOOGLE_CLIENT_SECRET.matcher(text).find()) {
                offenders.add(file); // file name only, never the match
            }
        }
        assertThat(offenders).as("files containing a Google client secret").isEmpty();
    }

    private static Path repoRoot() throws IOException, InterruptedException {
        List<String> out = git(Path.of("").toAbsolutePath(), "rev-parse", "--show-toplevel");
        return out == null || out.isEmpty() ? null : Path.of(out.get(0).trim());
    }

    private static List<String> gitFiles(Path root) throws IOException, InterruptedException {
        List<String> files = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard");
        assertThat(files).as("git ls-files output").isNotNull();
        List<String> names = new ArrayList<>();
        for (String chunk : files) {
            for (String name : chunk.split("\0")) {
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        assertThat(names).as("git lists this test itself").isNotEmpty();
        return names;
    }

    private static List<String> git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(false).start();
        } catch (IOException noGit) {
            return null;
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            return null;
        }
        return List.of(stdout);
    }
}
