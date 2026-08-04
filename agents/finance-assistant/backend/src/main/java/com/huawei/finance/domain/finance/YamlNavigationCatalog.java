package com.huawei.finance.domain.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;

final class YamlNavigationCatalog implements NavigationCatalogPort {
    private final Map<String, Map<String, Object>> entries;
    YamlNavigationCatalog() { entries = load(); }
    public Map<String, Object> find(String capabilityId) { return entries.getOrDefault(capabilityId, Map.of()); }
    public Set<String> capabilities() { return Set.copyOf(entries.keySet()); }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> load() {
        Path path = AgentAssetLocations.findInAssetRoots("menus/nav-meta.yaml")
                .orElseThrow(() -> new IllegalStateException("缺少 menus/nav-meta.yaml"));
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            loadMenuTree(yaml, "menus/menu-tree.yaml", out);
            loadMenuTree(yaml, "menus/screenshot-menu-tree.yaml", out);
            Map<String, Object> raw;
            try (InputStream in = Files.newInputStream(path)) {
                raw = yaml.readValue(in, Map.class);
            }
            raw.forEach((key, value) -> {
                if (key.startsWith("cap.nav.") && value instanceof Map<?, ?> map) {
                    out.put(key, (Map<String, Object>) map);
                }
            });
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("读取导航资产失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadMenuTree(ObjectMapper yaml, String relative,
                                     Map<String, Map<String, Object>> out) throws IOException {
        var located = AgentAssetLocations.findInAssetRoots(relative);
        if (located.isEmpty()) {
            return;
        }
        Map<String, Object> raw;
        try (InputStream in = Files.newInputStream(located.get())) {
            raw = yaml.readValue(in, Map.class);
        }
        Object menus = raw.get("menus");
        if (!(menus instanceof List<?> rows)) {
            return;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String techDomain = String.valueOf(map.get("techDomain"));
            String finalName = String.valueOf(map.get("finalName"));
            String capabilityId = "cap.nav." + techDomain + "_" + finalName;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("menuId", map.get("menuId"));
            entry.put("menuName", finalName);
            entry.put("bksPath", map.get("bksPath"));
            entry.put("path", map.get("path"));
            entry.put("techDomain", techDomain);
            out.putIfAbsent(capabilityId, Map.copyOf(entry));
        }
    }
}
