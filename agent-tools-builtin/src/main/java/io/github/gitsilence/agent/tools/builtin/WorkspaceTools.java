package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolOutputStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一组限制在指定工作区内的编码 Tool。
 *
 * <p>文件修改默认要求先读取，路径解析会检查符号链接逃逸，Bash 必须显式启用。
 * 各种大小和数量上限在生产者侧限制数据获取，而不仅依赖最终上下文截断。</p>
 */
public final class WorkspaceTools {

    private final Path root;
    private final Tool readFile;
    private final Tool writeFile;
    private final Tool edit;
    private final Tool applyPatch;
    private final Tool glob;
    private final Tool bash;
    private final List<Tool> tools;

    private WorkspaceTools(Builder builder) {
        validate(builder);
        // 所有文件类 Tool 共享同一个路径解析器、读取观察器和路径锁。
        WorkspacePathResolver paths = new WorkspacePathResolver(
            builder.root,
            builder.allowOutsideWorkspace,
            builder.toolOutputDirectory
        );
        FileObservationTracker observations =
            new FileObservationTracker(builder.requireReadBeforeMutation);
        PathLocks locks = new PathLocks();
        this.root = paths.getRoot();
        this.readFile = ReadFileTool.create(
            paths, observations, locks,
            builder.readLimit,
            builder.readMaxLineLength,
            builder.readMaxBytes
        );
        this.writeFile = WriteFileTool.create(
            paths, observations, locks, builder.maxWriteBytes
        );
        this.edit = EditTool.create(
            paths, observations, locks, builder.maxEditableBytes
        );
        this.applyPatch = ApplyPatchTool.create(
            paths,
            observations,
            locks,
            builder.maxPatchBytes,
            builder.maxPatchFiles,
            builder.maxPatchAffectedBytes,
            builder.maxWriteBytes,
            builder.maxEditableBytes
        );
        ToolOutputStore outputStore = new ToolOutputStore(
            builder.toolOutputDirectory
        );
        this.glob = GlobTool.create(
            paths, builder.globMaxResults, builder.globMaxScannedEntries,
            outputStore
        );
        Path spill = builder.bashSpillDirectory == null
            ? builder.toolOutputDirectory
            : paths.resolve(builder.bashSpillDirectory.toString());
        // Bash 默认不加入 tools 列表，调用方必须显式选择扩大执行能力。
        this.bash = builder.bashEnabled
            ? new BashTool(
                paths,
                builder.bashExecutable,
                builder.bashDefaultTimeoutMillis,
                builder.bashMaxTimeoutMillis,
                builder.bashMaxStreamBytes,
                spill
            )
            : null;
        List<Tool> assembled = new ArrayList<Tool>();
        assembled.add(readFile);
        assembled.add(writeFile);
        assembled.add(edit);
        assembled.add(applyPatch);
        assembled.add(glob);
        if (bash != null) assembled.add(bash);
        this.tools = Collections.unmodifiableList(assembled);
    }

    public static Builder builder(Path root) {
        return new Builder(root);
    }

    public static Builder builder(String root) {
        return builder(Paths.get(root));
    }

    /** 建议与使用该工具集的 Agent 系统指令合并的操作约束。 */
    public String getInstructions() {
        return "Use read_file instead of shell commands to inspect text files; "
            + "continue large files with offset and limit. Use glob instead "
            + "of shell find to discover files. Read an existing file before "
            + "write_file, edit, or apply_patch. Prefer edit for one exact "
            + "replacement and apply_patch for related multi-file changes."
            + (bash == null ? "" : " Check every bash exit-code, stderr, "
                + "timeout and truncation marker before continuing.");
    }

    public Path getRoot() { return root; }
    public Tool getReadFile() { return readFile; }
    public Tool getWriteFile() { return writeFile; }
    public Tool getEdit() { return edit; }
    public Tool getApplyPatch() { return applyPatch; }
    public Tool getGlob() { return glob; }
    public Optional<Tool> getBash() { return Optional.ofNullable(bash); }
    public List<Tool> getTools() { return tools; }

    private static void validate(Builder builder) {
        positive("readLimit", builder.readLimit);
        positive("readMaxLineLength", builder.readMaxLineLength);
        positive("readMaxBytes", builder.readMaxBytes);
        positive("maxWriteBytes", builder.maxWriteBytes);
        positive("maxEditableBytes", builder.maxEditableBytes);
        positive("maxPatchBytes", builder.maxPatchBytes);
        positive("maxPatchFiles", builder.maxPatchFiles);
        positive("maxPatchAffectedBytes", builder.maxPatchAffectedBytes);
        positive("globMaxResults", builder.globMaxResults);
        positive("globMaxScannedEntries", builder.globMaxScannedEntries);
        positive("bashDefaultTimeoutMillis", builder.bashDefaultTimeoutMillis);
        positive("bashMaxTimeoutMillis", builder.bashMaxTimeoutMillis);
        positive("bashMaxStreamBytes", builder.bashMaxStreamBytes);
        if (builder.bashDefaultTimeoutMillis > builder.bashMaxTimeoutMillis) {
            throw new IllegalArgumentException(
                "bashDefaultTimeoutMillis must not exceed bashMaxTimeoutMillis"
            );
        }
        long worstCaseLineBytes = (long) builder.readMaxLineLength * 4L + 128L;
        if (builder.readMaxBytes < worstCaseLineBytes) {
            throw new IllegalArgumentException(
                "readMaxBytes must be at least readMaxLineLength * 4 + 128"
            );
        }
        if (builder.bashExecutable.trim().isEmpty()) {
            throw new IllegalArgumentException("bashExecutable must not be blank");
        }
    }

    private static void positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public static final class Builder {
        private final Path root;
        private boolean allowOutsideWorkspace;
        private boolean requireReadBeforeMutation = true;
        private int readLimit = 2000;
        private int readMaxLineLength = 2000;
        private int readMaxBytes = 50 * 1024;
        private int maxWriteBytes = 5 * 1024 * 1024;
        private int maxEditableBytes = 5 * 1024 * 1024;
        private int maxPatchBytes = 1024 * 1024;
        private int maxPatchFiles = 100;
        private int maxPatchAffectedBytes = 20 * 1024 * 1024;
        private int globMaxResults = 100;
        private int globMaxScannedEntries = 100000;
        private boolean bashEnabled;
        private String bashExecutable = "/bin/bash";
        private int bashDefaultTimeoutMillis = 30000;
        private int bashMaxTimeoutMillis = 55000;
        private int bashMaxStreamBytes = 24 * 1024;
        private Path bashSpillDirectory;
        private Path toolOutputDirectory = ToolOutputStore.defaultDirectory();

        private Builder(Path root) {
            this.root = Objects.requireNonNull(root, "root");
        }

        public Builder allowOutsideWorkspace(boolean allow) {
            this.allowOutsideWorkspace = allow;
            return this;
        }

        public Builder requireReadBeforeMutation(boolean required) {
            this.requireReadBeforeMutation = required;
            return this;
        }

        public Builder readLimit(int value) {
            this.readLimit = value;
            return this;
        }

        public Builder readMaxLineLength(int value) {
            this.readMaxLineLength = value;
            return this;
        }

        public Builder readMaxBytes(int value) {
            this.readMaxBytes = value;
            return this;
        }

        public Builder maxWriteBytes(int value) {
            this.maxWriteBytes = value;
            return this;
        }

        public Builder maxEditableBytes(int value) {
            this.maxEditableBytes = value;
            return this;
        }

        /** 限制一次 {@code apply_patch} 调用的 UTF-8 输入字节数。 */
        public Builder maxPatchBytes(int value) {
            this.maxPatchBytes = value;
            return this;
        }

        /** 限制一次补丁涉及的不同源路径和目标路径总数。 */
        public Builder maxPatchFiles(int value) {
            this.maxPatchFiles = value;
            return this;
        }

        /** 限制补丁预检期间读取与生成的文本总字节数。 */
        public Builder maxPatchAffectedBytes(int value) {
            this.maxPatchAffectedBytes = value;
            return this;
        }

        public Builder globMaxResults(int value) {
            this.globMaxResults = value;
            return this;
        }

        public Builder globMaxScannedEntries(int value) {
            this.globMaxScannedEntries = value;
            return this;
        }

        public Builder enableBash(boolean enabled) {
            this.bashEnabled = enabled;
            return this;
        }

        public Builder bashExecutable(String executable) {
            this.bashExecutable = Objects.requireNonNull(executable, "executable");
            return this;
        }

        public Builder bashDefaultTimeoutMillis(int value) {
            this.bashDefaultTimeoutMillis = value;
            return this;
        }

        public Builder bashMaxTimeoutMillis(int value) {
            this.bashMaxTimeoutMillis = value;
            return this;
        }

        public Builder bashMaxStreamBytes(int value) {
            this.bashMaxStreamBytes = value;
            return this;
        }

        public Builder bashSpillDirectory(Path directory) {
            this.bashSpillDirectory = directory;
            return this;
        }

        public Builder toolOutputDirectory(Path directory) {
            this.toolOutputDirectory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
            return this;
        }

        public WorkspaceTools build() {
            return new WorkspaceTools(this);
        }
    }
}
