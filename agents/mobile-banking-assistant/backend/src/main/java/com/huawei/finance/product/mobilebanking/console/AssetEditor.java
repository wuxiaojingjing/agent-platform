package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLint;
import com.huawei.finance.registry.asset.AssetStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产文件的读与写。
 *
 * <p>做成**文件级**编辑而不是按字段的结构化表单，是因为资产本来就是文件，且归 Git 管。
 * 结构化表单会引入第二种真值形态：页面上改的是表单模型，落盘还要序列化回 YAML，
 * 而那份序列化结果与人手写的注释、顺序、缩进不可能一致——第一次有人同时用页面和 Git 改
 * 同一个文件，diff 就会变成一场灾难。文件级编辑保留原样，页面只是个带校验的编辑器。
 *
 * <p>写入是**校验后才算数**的：先备份、再落盘、再整份重载、再跑 Lint，
 * 任一步失败就把文件恢复回去并重新载回旧资产。这样失败的写入不会留下一个
 * 「加载不出来但已经在磁盘上」的资产目录——那种状态下一次重启就起不来了。
 *
 * <p>阶段 D1：共享根（规则/模板/…）与 {@code agents/&lt;id&gt;/assets} 一并列出。
 * 逻辑路径约定：
 * <ul>
 *   <li>共享根：相对路径，如 {@code rules/fusion.yaml}</li>
 *   <li>域资产：{@code agents/&lt;id&gt;/…}，相对该域 {@code assets/} 根
 *       （如 {@code agents/account/capabilities/account.yaml}）</li>
 * </ul>
 */
public class AssetEditor {

    private static final Logger log = LoggerFactory.getLogger(AssetEditor.class);

    private static final String AGENT_PREFIX = "agents/";

    /**
     * 允许编辑的扩展名。
     *
     * <p>白名单而不是黑名单：黑名单挡不住明天新增的那种文件类型，而这个接口能写文件，
     * 挡不住的后果是从「改配置」变成「往服务器上放东西」。
     */
    private static final Set<String> EDITABLE_SUFFIXES = Set.of(".yaml", ".yml", ".ftl", ".json");

    private final AssetStore store;

    public AssetEditor(AssetStore store) {
        this.store = store;
    }

    /** 可编辑的资产文件清单（共享根 + 各 Agent 资产根），按相对路径排序。 */
    public List<FileEntry> list() {
        List<FileEntry> files = new ArrayList<>();
        Path shared = store.root();
        collectUnder(shared, "", files);
        for (Path agentAssets : AgentAssetLocations.discoverAgentAssetRoots(shared)) {
            String agentId = agentAssets.getParent().getFileName().toString();
            collectUnder(agentAssets, AGENT_PREFIX + agentId + "/", files);
        }
        files.sort(Comparator.comparing(FileEntry::path));
        return files;
    }

    private static void collectUnder(Path root, String pathPrefix, List<FileEntry> into) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(AssetEditor::editable)
                    .forEach(p -> into.add(describe(root, p, pathPrefix)));
        } catch (IOException e) {
            throw new UncheckedIOException("列不出资产目录：" + root, e);
        }
    }

    public String read(String relativePath) {
        try {
            return Files.readString(resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不出资产文件：" + relativePath, e);
        }
    }

    /**
     * 写入并让它生效。
     *
     * <p>Lint 的 ERROR 会导致回滚，WARN 不会——与 CI 的口径一致（CI 只对 ERROR 拒绝合并）。
     * 让 WARN 也回滚，等于把「需要人判断的建议」变成了硬门禁，而它拦下的第一批多半是
     * 行内历史卡；结果不是资产变好，是这个功能被绕开。
     *
     * @return 生效后的资产版本与 Lint 结果
     */
    public WriteResult write(String relativePath, String content) {
        Path file = resolve(relativePath);
        String backup;
        try {
            backup = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入前读不出原文件，放弃本次修改：" + relativePath, e);
        }

        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("落盘失败：" + relativePath, e);
        }

        AssetBundle reloaded;
        try {
            reloaded = store.reload();
        } catch (RuntimeException e) {
            rollback(file, backup, relativePath);
            throw new AssetWriteRejected("新资产加载失败，已回滚：" + rootCause(e), List.of());
        }

        List<AssetLint.Finding> findings = AssetLint.inspect(reloaded);
        List<AssetLint.Finding> errors = findings.stream()
                .filter(f -> f.severity() == AssetLint.Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            rollback(file, backup, relativePath);
            throw new AssetWriteRejected("资产校验未通过，已回滚", errors);
        }

        log.info("资产已更新 file={} version={} 告警={}",
                relativePath, reloaded.assetVersion(), findings.size() - errors.size());
        return new WriteResult(reloaded.assetVersion(), findings);
    }

    /** 回滚失败是真正的坏情况：磁盘上留着一份加载不出来的资产，下次重启就起不来。 */
    private void rollback(Path file, String backup, String relativePath) {
        try {
            Files.writeString(file, backup, StandardCharsets.UTF_8);
            store.reload();
            log.info("已回滚资产文件 file={}", relativePath);
        } catch (IOException | RuntimeException e) {
            log.error("回滚失败！磁盘上现在是一份未通过校验的资产，重启会起不来 file={} cause={}",
                    relativePath, e.toString());
        }
    }

    /**
     * 把逻辑路径解析到真实文件。
     *
     * <p>规范化之后再比对前缀，{@code ../} 这类越界写法在这里被挡住。少了这一步，
     * 这个接口就等于「任意文件写」。
     */
    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("未指定资产文件");
        }
        // 绝对路径 / Windows 盘符：resolve 会直接吃掉相对根，必须先拒
        if (relativePath.startsWith("/")
                || (relativePath.length() > 1 && relativePath.charAt(1) == ':')) {
            throw new IllegalArgumentException("路径越出资产目录：" + relativePath);
        }
        if (relativePath.contains("..")) {
            throw new IllegalArgumentException("路径越出资产目录：" + relativePath);
        }

        Path root;
        Path target;
        if (relativePath.startsWith(AGENT_PREFIX)) {
            String rest = relativePath.substring(AGENT_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash >= rest.length() - 1) {
                throw new IllegalArgumentException("域资产路径须为 agents/<id>/…：" + relativePath);
            }
            String agentId = rest.substring(0, slash);
            String underAssets = rest.substring(slash + 1);
            if (agentId.isBlank() || agentId.indexOf('/') >= 0) {
                throw new IllegalArgumentException("域资产路径须为 agents/<id>/…：" + relativePath);
            }
            root = AgentAssetLocations.discoverAgentAssetRoots(store.root()).stream()
                    .filter(candidate -> candidate.getParent().getFileName().toString().equals(agentId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "域资产目录不存在：" + relativePath));
            target = root.resolve(underAssets).toAbsolutePath().normalize();
        } else {
            root = store.root().toAbsolutePath().normalize();
            target = root.resolve(relativePath).toAbsolutePath().normalize();
        }

        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径越出资产目录：" + relativePath);
        }
        if (!editable(target)) {
            throw new IllegalArgumentException("该类型的文件不允许经控制台编辑：" + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("资产文件不存在：" + relativePath);
        }
        return target;
    }

    private static boolean editable(Path path) {
        String name = path.getFileName().toString();
        return EDITABLE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static FileEntry describe(Path root, Path file, String pathPrefix) {
        String relative = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
        String logical = pathPrefix + relative;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        return new FileEntry(logical, category(logical), size);
    }

    /** 归类只为前端分组显示，不参与任何判定。 */
    static String category(String relative) {
        if (relative.startsWith(AGENT_PREFIX)) {
            String rest = relative.substring(AGENT_PREFIX.length());
            int slash = rest.indexOf('/');
            String agentId = slash > 0 ? rest.substring(0, slash) : rest;
            String under = slash > 0 ? rest.substring(slash + 1) : "";
            if (under.startsWith("capabilities/")) {
                return "能力卡 · " + agentId;
            }
            if (under.startsWith("menus/")) {
                return "菜单 · " + agentId;
            }
            return "域资产 · " + agentId;
        }
        if (relative.startsWith("capabilities/")) {
            return "能力卡";
        }
        if (relative.startsWith("rules/")) {
            return "规则与融合";
        }
        if (relative.startsWith("templates/")) {
            return "话术模板";
        }
        if (relative.startsWith("compliance/")) {
            return "合规话题";
        }
        if (relative.startsWith("prompts/")) {
            return "提示词";
        }
        if (relative.startsWith("menus/") || relative.startsWith("domains/")) {
            return "目录与菜单";
        }
        return "其他";
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    public record FileEntry(String path, String category, long size) {
    }

    public record WriteResult(String assetVersion, List<AssetLint.Finding> findings) {
    }

    /** 写入被校验拦下。带上具体发现，前端要把它们摆给操作者看，而不是只说一句失败。 */
    public static class AssetWriteRejected extends RuntimeException {

        private final transient List<AssetLint.Finding> findings;

        public AssetWriteRejected(String message, List<AssetLint.Finding> findings) {
            super(message);
            this.findings = findings;
        }

        public List<AssetLint.Finding> findings() {
            return findings;
        }
    }
}
