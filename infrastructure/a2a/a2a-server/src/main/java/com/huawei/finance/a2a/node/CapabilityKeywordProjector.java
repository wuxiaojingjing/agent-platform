package com.huawei.finance.a2a.node;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * 从能力卡资产读出「能力 ID → 目标关键词」，喂给 {@link KeywordGoalResolver}。
 *
 * <p>只读 {@code type != AGENT} 的叶子能力:父卡（{@code agent.*}）不是可执行能力，
 * 把它读进来会让 GOAL 落到一张父卡上，然后在 {@code supports} 处才被否掉。
 *
 * <p>单张卡读坏只跳过该卡，不让整个解析器起不来:一张卡的 keywords 写成了字符串而不是数组，
 * 代价应该是这条能力暂时不参与 GOAL 解析，而不是全平台的 GOAL 都失效。
 *
 * <p>阶段 D1：支持多资源模式（共享根 + {@code agents/<id>/assets}）。
 */
public class CapabilityKeywordProjector {

    private static final Logger log = LoggerFactory.getLogger(CapabilityKeywordProjector.class);

    private final List<String> locations;

    public CapabilityKeywordProjector(String location) {
        this(List.of(location));
    }

    public CapabilityKeywordProjector(List<String> locations) {
        this.locations = List.copyOf(locations);
    }

    public Map<String, List<String>> project() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String location : locations) {
            try {
                Resource[] resources = resolver.getResources(location);
                for (Resource resource : resources) {
                    readOne(resource, keywords);
                }
            } catch (IOException e) {
                log.error("能力卡资产读取失败 location={}", location, e);
            }
        }
        log.info("GOAL 关键词投影完成 能力数={} patterns={}", keywords.size(), locations.size());
        return keywords;
    }

    @SuppressWarnings("unchecked")
    private void readOne(Resource resource, Map<String, List<String>> into) {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof List<?> rows)) {
                return;
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> card = (Map<String, Object>) map;
                Object id = card.get("capabilityId");
                if (id == null || "AGENT".equals(String.valueOf(card.get("type")))) {
                    continue;
                }
                List<String> words = wordsOf(card.get("keywords"));
                if (!words.isEmpty()) {
                    into.put(String.valueOf(id), words);
                }
            }
        } catch (RuntimeException | IOException e) {
            log.warn("单张能力卡读取失败，跳过 resource={}", resource.getFilename(), e);
        }
    }

    private static List<String> wordsOf(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                words.add(String.valueOf(item).trim());
            }
        }
        return words;
    }
}
