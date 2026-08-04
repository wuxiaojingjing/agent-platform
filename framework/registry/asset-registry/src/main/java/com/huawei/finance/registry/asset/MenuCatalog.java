package com.huawei.finance.registry.asset;

import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;

/**
 * 手机银行菜单树（爱存不存）。用于菜单跳转能力与溯源。
 */
public class MenuCatalog {

    private String version = "0";
    private String source = "";
    private List<MenuEntry> menus = List.of();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "0" : version;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source == null ? "" : source;
    }

    public List<MenuEntry> getMenus() {
        return menus;
    }

    public void setMenus(List<MenuEntry> menus) {
        this.menus = menus == null ? List.of() : List.copyOf(menus);
    }

    public Optional<MenuEntry> find(String menuId) {
        if (menuId == null) {
            return Optional.empty();
        }
        return menus.stream().filter(m -> menuId.equals(m.getMenuId())).findFirst();
    }

    public Optional<MenuEntry> findByFinalName(String finalName) {
        if (finalName == null) {
            return Optional.empty();
        }
        return menus.stream().filter(m -> finalName.equals(m.getFinalName())).findFirst();
    }

    /** 主菜单树与人工复核补充树按 menuId 合并；补充资产不得静默覆盖生成资产。 */
    public static MenuCatalog merge(MenuCatalog primary, MenuCatalog supplemental) {
        MenuCatalog merged = new MenuCatalog();
        merged.setVersion(primary.getVersion() + "+" + supplemental.getVersion());
        merged.setSource(primary.getSource() + ";" + supplemental.getSource());
        LinkedHashMap<String, MenuEntry> entries = new LinkedHashMap<>();
        for (MenuEntry entry : primary.getMenus()) {
            entries.put(entry.getMenuId(), entry);
        }
        for (MenuEntry entry : supplemental.getMenus()) {
            if (entries.putIfAbsent(entry.getMenuId(), entry) != null) {
                throw new IllegalStateException("补充菜单与主菜单 menuId 重复：" + entry.getMenuId());
            }
        }
        merged.setMenus(List.copyOf(entries.values()));
        return merged;
    }

    /** cap.nav.{techDomain}_{finalName} 与菜单资产的确定性映射。 */
    public Optional<MenuEntry> findByCapabilityId(String capabilityId) {
        if (capabilityId == null) return Optional.empty();
        return menus.stream().filter(menu -> capabilityId.equals(
                "cap.nav." + menu.getTechDomain() + "_" + menu.getFinalName())).findFirst();
    }

    /** 首批导航切片：指定科技域 + 可选仅工作流。 */
    public List<MenuEntry> forTechDomains(List<String> techCodes, boolean workflowsFirst) {
        if (techCodes == null || techCodes.isEmpty()) {
            return List.of();
        }
        var set = java.util.Set.copyOf(techCodes);
        return menus.stream()
                .filter(m -> set.contains(m.getTechDomain()))
                .sorted((a, b) -> {
                    if (workflowsFirst) {
                        int ka = "workflow".equals(a.getKind()) ? 0 : 1;
                        int kb = "workflow".equals(b.getKind()) ? 0 : 1;
                        if (ka != kb) {
                            return Integer.compare(ka, kb);
                        }
                    }
                    return a.getFinalName().compareTo(b.getFinalName());
                })
                .toList();
    }

    public static MenuCatalog empty() {
        return new MenuCatalog();
    }

    public static final class MenuEntry {
        private String menuId;
        private String businessDomain;
        private String techDomain;
        private String path;
        private String finalName;
        private String bksPath;
        private String kind;

        public String getMenuId() {
            return menuId;
        }

        public void setMenuId(String menuId) {
            this.menuId = menuId;
        }

        public String getBusinessDomain() {
            return businessDomain;
        }

        public void setBusinessDomain(String businessDomain) {
            this.businessDomain = businessDomain;
        }

        public String getTechDomain() {
            return techDomain;
        }

        public void setTechDomain(String techDomain) {
            this.techDomain = techDomain;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getFinalName() {
            return finalName;
        }

        public void setFinalName(String finalName) {
            this.finalName = finalName;
        }

        public String getBksPath() {
            return bksPath;
        }

        public void setBksPath(String bksPath) {
            this.bksPath = bksPath;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }
    }
}
