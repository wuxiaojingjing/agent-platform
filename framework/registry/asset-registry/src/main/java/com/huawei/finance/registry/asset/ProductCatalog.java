package com.huawei.finance.registry.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Published product identities used to ground product mentions before capability execution. */
public class ProductCatalog {
    private List<ProductEntity> products = List.of();

    public List<ProductEntity> getProducts() {
        return products;
    }

    public void setProducts(List<ProductEntity> products) {
        List<ProductEntity> copy = products == null ? List.of() : List.copyOf(products);
        validate(copy);
        this.products = copy;
    }

    /** Returns unique entity matches in the same order in which users mentioned them. */
    public List<Match> resolveMentions(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<Match> occurrences = new ArrayList<>();
        for (ProductEntity product : products) {
            for (String alias : product.allAliases()) {
                int from = 0;
                while (from < query.length()) {
                    int at = query.indexOf(alias, from);
                    if (at < 0) break;
                    occurrences.add(new Match(product, alias, at));
                    from = at + Math.max(1, alias.length());
                }
            }
        }
        occurrences.sort(Comparator.comparingInt(Match::offset)
                .thenComparing((Match match) -> match.alias().length(), Comparator.reverseOrder())
                .thenComparing(match -> match.entity().entityId()));
        List<Match> selected = new ArrayList<>();
        LinkedHashSet<String> selectedEntities = new LinkedHashSet<>();
        for (Match candidate : occurrences) {
            if (selectedEntities.contains(candidate.entity().entityId())) continue;
            int start = candidate.offset();
            int end = start + candidate.alias().length();
            boolean overlaps = selected.stream().anyMatch(existing -> {
                int existingEnd = existing.offset() + existing.alias().length();
                return start < existingEnd && existing.offset() < end;
            });
            if (!overlaps) {
                selected.add(candidate);
                selectedEntities.add(candidate.entity().entityId());
            }
        }
        return List.copyOf(selected);
    }

    private static void validate(List<ProductEntity> products) {
        Map<String, String> aliasOwners = new LinkedHashMap<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ProductEntity product : products) {
            if (product == null || blank(product.entityId()) || blank(product.displayName())
                    || blank(product.productType()) || blank(product.queryCapabilityId())
                    || blank(product.ownerAgentId())) {
                throw new IllegalArgumentException("产品实体字段不得为空");
            }
            if (!ids.add(product.entityId())) {
                throw new IllegalArgumentException("产品实体 ID 重复：" + product.entityId());
            }
            for (String alias : product.allAliases()) {
                String previous = aliasOwners.putIfAbsent(alias, product.entityId());
                if (previous != null && !previous.equals(product.entityId())) {
                    throw new IllegalArgumentException("产品别名映射冲突：" + alias);
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ProductEntity(String entityId, String displayName, List<String> aliases,
                                String productType, String queryCapabilityId,
                                String ownerAgentId) {
        public ProductEntity {
            aliases = aliases == null ? List.of() : aliases.stream()
                    .filter(alias -> alias != null && !alias.isBlank()).distinct().toList();
        }

        List<String> allAliases() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            values.add(displayName);
            values.addAll(aliases);
            return List.copyOf(values);
        }
    }

    public record Match(ProductEntity entity, String alias, int offset) {}
}
