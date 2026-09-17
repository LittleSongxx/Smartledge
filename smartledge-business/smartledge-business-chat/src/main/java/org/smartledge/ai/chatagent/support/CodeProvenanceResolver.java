package org.smartledge.ai.chatagent.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures one code identity at process startup. Packaged builds can provide
 * git.properties; local IDEA runs fall back to the current repository.
 */
@Slf4j
@Component
public final class CodeProvenanceResolver {

    private static final long GIT_TIMEOUT_MILLIS = 2_000L;

    private final String identity;

    public CodeProvenanceResolver() {
        this.identity = resolveIdentity();
        if (identity.isBlank()) {
            log.warn("未能自动解析当前运行代码版本，Evaluation Snapshot 将保留 MISSING_CODE_COMMIT");
        }
        else {
            log.info("已自动绑定 Evaluation Snapshot 代码版本: {}", identity);
        }
    }

    public String currentIdentity() {
        return identity;
    }

    static String identity(String headCommit, String workingTreeState) {
        String head = normalize(headCommit);
        if (head.isBlank()) {
            return "";
        }
        String state = normalize(workingTreeState);
        if (state.isBlank()) {
            return head;
        }
        return head + "+dirty:" + sha256(state.getBytes(StandardCharsets.UTF_8));
    }

    static String packagedIdentity(String headCommit, boolean dirty, String artifactDigest) {
        String head = normalize(headCommit);
        if (head.isBlank()) {
            return "";
        }
        if (!dirty) {
            return head;
        }
        String digest = normalize(artifactDigest);
        return digest.isBlank() ? "" : head + "+dirty:" + digest;
    }

    private String resolveIdentity() {
        BuildGitMetadata buildMetadata = readGitProperties();
        Path artifact = codeArtifact(codeSource());
        if (artifact != null) {
            return packagedIdentity(buildMetadata.commit(), buildMetadata.dirty(), artifactDigest(artifact));
        }

        Path repository = findRepository(Path.of(System.getProperty("user.dir", ".")));
        if (repository != null) {
            CommandResult headResult = command(repository, "rev-parse", "--verify", "HEAD");
            String head = new String(headResult.output(), StandardCharsets.UTF_8).trim();
            String state = workingTreeState(repository);
            if (headResult.success() && !head.isBlank() && state != null) {
                return identity(head, state);
            }
        }
        return "";
    }

    private BuildGitMetadata readGitProperties() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            if (input == null) {
                return BuildGitMetadata.EMPTY;
            }
            Properties properties = new Properties();
            properties.load(input);
            return new BuildGitMetadata(
                firstNonBlank(
                    properties.getProperty("git.commit.id.full"),
                    properties.getProperty("git.commit.id")
                ),
                Boolean.parseBoolean(properties.getProperty("git.dirty", "false"))
            );
        }
        catch (IOException exception) {
            log.debug("读取 git.properties 失败", exception);
            return BuildGitMetadata.EMPTY;
        }
    }

    private Path codeSource() {
        try {
            if (getClass().getProtectionDomain() == null
                || getClass().getProtectionDomain().getCodeSource() == null
                || getClass().getProtectionDomain().getCodeSource().getLocation() == null) {
                return null;
            }
            return Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        catch (Exception exception) {
            log.debug("解析运行代码位置失败", exception);
            return null;
        }
    }

    private Path codeArtifact(Path codeSource) {
        if (codeSource != null && Files.isRegularFile(codeSource, LinkOption.NOFOLLOW_LINKS)) {
            return codeSource;
        }
        String[] classPathEntries = System.getProperty("java.class.path", "").split(
            java.util.regex.Pattern.quote(File.pathSeparator)
        );
        if (classPathEntries.length != 1) {
            return null;
        }
        Path candidate = Path.of(classPathEntries[0]).toAbsolutePath().normalize();
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
    }

    private String workingTreeState(Path repository) {
        CommandResult changed = command(repository, "diff", "--name-only", "-z", "HEAD", "--");
        CommandResult untracked = command(repository, "ls-files", "--others", "--exclude-standard", "-z");
        if (!changed.success() || !untracked.success()) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        paths.addAll(nullDelimited(changed.output()));
        paths.addAll(nullDelimited(untracked.output()));
        return paths.stream()
            .distinct()
            .sorted()
            .map(path -> path + "=" + workingTreePathIdentity(repository.resolve(path)))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private List<String> nullDelimited(byte[] value) {
        if (value.length == 0) {
            return List.of();
        }
        String text = new String(value, StandardCharsets.UTF_8);
        return List.of(text.split("\\u0000", -1)).stream()
            .filter(path -> !path.isBlank())
            .toList();
    }

    private String workingTreePathIdentity(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return "DELETED";
            }
            if (Files.isSymbolicLink(path)) {
                return "SYMLINK:" + sha256(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "NON_REGULAR";
            }
            return artifactDigest(path);
        }
        catch (IOException exception) {
            return "UNREADABLE:" + exception.getClass().getSimpleName();
        }
    }

    private String artifactDigest(Path path) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException | NoSuchAlgorithmException exception) {
            return "";
        }
    }

    private Path findRepository(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private CommandResult command(Path repository, String... arguments) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(repository.toString());
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicReference<IOException> readFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (InputStream input = process.getInputStream()) {
                    input.transferTo(output);
                }
                catch (IOException exception) {
                    readFailure.set(exception);
                }
            }, "evaluation-git-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(GIT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(500L);
                return CommandResult.FAILURE;
            }
            reader.join(500L);
            if (reader.isAlive() || process.exitValue() != 0 || readFailure.get() != null) {
                reader.interrupt();
                return CommandResult.FAILURE;
            }
            return new CommandResult(true, output.toByteArray());
        }
        catch (IOException exception) {
            log.debug("执行 Git provenance 命令失败", exception);
            return CommandResult.FAILURE;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CommandResult.FAILURE;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record BuildGitMetadata(String commit, boolean dirty) {

        private static final BuildGitMetadata EMPTY = new BuildGitMetadata("", false);
    }

    private record CommandResult(boolean success, byte[] output) {

        private static final CommandResult FAILURE = new CommandResult(false, new byte[0]);
    }
}
