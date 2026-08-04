package com.huawei.finance.registry.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 科技领域枚举（v0.7 附录 F）。
 *
 * <p>路由 / 召回 / CapabilityCard.domains 使用 {@link Domain#code}；
 * {@code aliases} 承接历史短码（wealth → wealth_aggregate）。
 */
public class TechDomainCatalog {

    private String version = "0";
    private String source = "";
    private Map<String, String> aliases = Map.of();
    private List<Domain> domains = List.of();

    private transient Map<String, Domain> byCode = Map.of();

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

    public Map<String, String> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
    }

    public List<Domain> getDomains() {
        return domains;
    }

    public void setDomains(List<Domain> domains) {
        this.domains = domains == null ? List.of() : List.copyOf(domains);
        Map<String, Domain> index = new LinkedHashMap<>();
        for (Domain d : this.domains) {
            if (d != null && d.getCode() != null) {
                index.put(d.getCode(), d);
            }
        }
        this.byCode = Map.copyOf(index);
    }

    public Set<String> codes() {
        return byCode.keySet();
    }

    public boolean isKnown(String code) {
        return code != null && byCode.containsKey(code);
    }

    /**
     * 把历史短码或已是规范码的值规范成科技域码。
     *
     * @return 规范码；无法解释时原样返回（交给 lint 报错）
     */
    public String canonicalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (byCode.containsKey(raw)) {
            return raw;
        }
        String aliased = aliases.get(raw);
        return aliased == null ? raw : aliased;
    }

    public List<String> canonicalizeAll(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(this::canonicalize).distinct().toList();
    }

    public static TechDomainCatalog empty() {
        return new TechDomainCatalog();
    }

    public static final class Domain {
        private String code;
        private String name;
        private String businessDomain;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBusinessDomain() {
            return businessDomain;
        }

        public void setBusinessDomain(String businessDomain) {
            this.businessDomain = businessDomain;
        }
    }
}
