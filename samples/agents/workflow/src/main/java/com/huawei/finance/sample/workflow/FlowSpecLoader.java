package com.huawei.finance.sample.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从资产目录读办理流程声明。
 *
 * <p>与能力卡一样放在仓库目录、走 Git MR 评审：改一步办理顺序不该需要重新发布应用
 * （实施架构 §5.1）。同样沿用「加载即校验」——声明不合法就拒绝启动，
 * 而不是等到某个用户恰好办这笔业务时才炸。
 */
public class FlowSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(FlowSpecLoader.class);

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            // 未知字段直接失败：YAML 里把 opration 拼错成这样时，宽容解析会让那一行静默消失
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public List<FlowSpec> load(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("办理流程目录不存在：" + dir.toAbsolutePath());
        }

        List<FlowSpec> specs = new ArrayList<>();
        for (Path file : listYaml(dir)) {
            FlowSpec spec = read(file);
            if (spec.capabilityId() == null || spec.capabilityId().isBlank()) {
                throw new IllegalStateException("流程声明缺 capabilityId：" + file.getFileName());
            }
            boolean duplicated = specs.stream()
                    .anyMatch(existing -> existing.capabilityId().equals(spec.capabilityId()));
            if (duplicated) {
                // 一个能力两份流程，运行期只能靠加载顺序决定用哪份
                throw new IllegalStateException("同一能力有多份流程声明：" + spec.capabilityId());
            }
            specs.add(spec);
        }
        log.info("办理流程加载完成 目录={} 数量={} 能力={}", dir, specs.size(),
                specs.stream().map(FlowSpec::capabilityId).toList());
        return List.copyOf(specs);
    }

    private FlowSpec read(Path file) {
        try {
            JsonNode node = yaml.readTree(Files.readString(file));
            if (node == null || node.isNull()) {
                throw new IllegalStateException("流程声明为空：" + file.getFileName());
            }
            return yaml.treeToValue(node, FlowSpec.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了流程声明：" + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("流程声明不合结构 file=" + file.getFileName()
                    + " 原因=" + e.getMessage(), e);
        }
    }

    private List<Path> listYaml(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列不了流程目录：" + dir, e);
        }
    }
}
