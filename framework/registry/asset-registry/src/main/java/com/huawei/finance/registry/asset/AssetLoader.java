package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.contracts.validation.ValidationOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从资产目录加载全部资产。
 *
 * <p>资产放在仓库目录而非 jar 内，是为了让能力卡与话术能走 Git MR 评审、按需回滚，
 * 不必为改一句话术重新构建应用（实施架构 §5.1 Registry 以 Git 为存储）。
 *
 * <p>加载即校验：能力卡不合 Schema 直接拒绝启动。让不合法的卡进内存，
 * 错误会推迟到某个用户恰好命中它时才爆发。
 *
 * <p>阶段 D1：{@link #load(Path)} 的入参是<strong>共享根</strong>（含 manifest）；
 * 能力卡还从同仓 {@code agents/<id>/assets} 合并加载。
 */
public class AssetLoader {

    private static final Logger log = LoggerFactory.getLogger(AssetLoader.class);

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ContractValidator validator;

    public AssetLoader(ContractValidator validator) {
        this.validator = validator;
    }

    /**
     * 通过显式 Agent home 或 Maven Reactor 开发回退定位 {@code assets/}，找不到就抛。
     *
     * <p>实现在 {@link AgentAssetLocations#requireAssets()}；这里保留一层加载器门面，
     * 让调用方不需要重复处理运行环境与开发环境的路径差异。
     */
    public static Path locateAssets() {
        return AgentAssetLocations.requireAssets();
    }

    /** 共享根 + 自动发现的 {@code agents/<id>/assets}。 */
    public AssetBundle loadDefault() {
        return load(locateAssets());
    }

    /**
     * 以共享根加载：单例文件只读该根；能力卡合并该根与旁路 {@code agents/<id>/assets}。
     *
     * <p>夹具目录若无旁路 Agent 资产，行为与单根一致。
     */
    public AssetBundle load(Path sharedRoot) {
        if (!Files.isDirectory(sharedRoot)) {
            throw new IllegalStateException("资产目录不存在：" + sharedRoot.toAbsolutePath());
        }
        List<Path> roots = AgentAssetLocations.allAssetRoots(sharedRoot);
        return load(sharedRoot, roots);
    }

    /**
     * @param sharedRoot 共享根（manifest / rules / templates …）
     * @param roots      参与能力卡与摘要的全部根（应含 sharedRoot）
     */
    public AssetBundle load(Path sharedRoot, List<Path> roots) {
        if (!Files.isDirectory(sharedRoot)) {
            throw new IllegalStateException("资产目录不存在：" + sharedRoot.toAbsolutePath());
        }
        if (roots == null || roots.isEmpty()) {
            roots = List.of(sharedRoot);
        }

        JsonNode manifest = readYaml(sharedRoot.resolve("manifest.yaml"));
        String declaredVersion = manifest.path("version").asText("unknown");

        TechDomainCatalog techDomains = loadTechDomains(sharedRoot.resolve("domains/tech-domains.yaml"));
        MenuCatalog menus = loadMenus(sharedRoot.resolve("menus/menu-tree.yaml"),
                sharedRoot.resolve("menus/screenshot-menu-tree.yaml"));
        StandardQaBank standardQa = readValue(sharedRoot.resolve("standard-qa.yaml"), StandardQaBank.class);
        List<CapabilityCard> capabilities = projectKnowledgeMenus(
                loadCapabilitiesFromRoots(roots, techDomains), standardQa, menus);
        List<StrongRule> strongRules = loadStrongRules(roots, sharedRoot);
        List<NegativeRule> negativeRules = loadNegativeRules(sharedRoot.resolve("rules/negative-rules.yaml"));
        FusionConfig fusion = readValue(sharedRoot.resolve("rules/fusion.yaml"), FusionConfig.class);
        SynonymTable synonyms = readValue(sharedRoot.resolve("synonyms.yaml"), SynonymTable.class);
        ClarifyConfig clarify = readValue(sharedRoot.resolve("clarify.yaml"), ClarifyConfig.class);
        Templates templates = loadTemplates(sharedRoot.resolve("templates"));
        ArbitrationSkill skill = readValue(sharedRoot.resolve("prompts/arbitration-skill.yaml"), ArbitrationSkill.class);
        ArbitrationSkill contextRewriteSkill = readValue(
                sharedRoot.resolve("prompts/context-rewrite-skill.yaml"), ArbitrationSkill.class);
        ArbitrationSkill continuationSkill = readValue(
                sharedRoot.resolve("prompts/continuation-skill.yaml"), ArbitrationSkill.class);
        ArbitrationSkill loopPlannerSkill = readValue(
                sharedRoot.resolve("prompts/loop-planner-skill.yaml"), ArbitrationSkill.class);
        ComplianceTopics complianceTopics = readValue(sharedRoot.resolve("compliance/topics.yaml"),
                ComplianceTopics.class);
        ResponsePolicy responsePolicy = Files.isRegularFile(sharedRoot.resolve("response-policy.yaml"))
                ? readValue(sharedRoot.resolve("response-policy.yaml"), ResponsePolicy.class)
                : new ResponsePolicy();
        ProductCatalog productCatalog = Files.isRegularFile(sharedRoot.resolve("products/catalog.yaml"))
                ? readValue(sharedRoot.resolve("products/catalog.yaml"), ProductCatalog.class)
                : new ProductCatalog();
        productCatalog.setProducts(productCatalog.getProducts());
        validateProductCatalog(productCatalog, capabilities);
        ProductComparisonPolicy productComparisonPolicy = Files.isRegularFile(
                sharedRoot.resolve("policies/product-comparison.yaml"))
                ? readValue(sharedRoot.resolve("policies/product-comparison.yaml"),
                        ProductComparisonPolicy.class)
                : new ProductComparisonPolicy();
        productComparisonPolicy.validate(productCatalog);

        String digest = digestOf(roots);
        String assetVersion = declaredVersion + "+" + digest;

        log.info("资产加载完成 version={} 根数={} 能力卡={} 科技域={} 菜单={} 强规则={} 负向规则={} 模板={} 仲裁提示词={} 合规话题={} 标准问答={}",
                assetVersion, roots.size(), capabilities.size(), techDomains.getDomains().size(),
                menus.getMenus().size(),
                strongRules.size(), negativeRules.size(),
                templates.defs().size(), skill.getVersion(), complianceTopics.getTopics().size(),
                standardQa.getItems().size());

        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules,
                negativeRules, fusion, synonyms, clarify, templates.defs(), templates.byCapability(), skill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill,
                complianceTopics, standardQa, techDomains, menus, responsePolicy, productCatalog,
                productComparisonPolicy);
    }

    private static void validateProductCatalog(ProductCatalog catalog, List<CapabilityCard> capabilities) {
        Map<String, CapabilityCard> cards = capabilities.stream().collect(
                java.util.stream.Collectors.toMap(CapabilityCard::capabilityId, card -> card));
        for (ProductCatalog.ProductEntity product : catalog.getProducts()) {
            CapabilityCard query = cards.get(product.queryCapabilityId());
            if (query == null) {
                throw new IllegalStateException("产品实体 " + product.entityId()
                        + " 指向不存在的查询能力：" + product.queryCapabilityId());
            }
            if (!product.ownerAgentId().equals(query.parentCapabilityId())) {
                throw new IllegalStateException("产品实体 " + product.entityId()
                        + " 的 ownerAgentId 与能力父 Agent 不一致");
            }
        }
    }

    private TechDomainCatalog loadTechDomains(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("科技领域枚举缺失：" + file
                    + "（附录 F，由 scripts/import_domain_menu_assets.py 生成）");
        }
        TechDomainCatalog catalog = readValue(file, TechDomainCatalog.class);
        if (catalog.getDomains().isEmpty()) {
            throw new IllegalStateException("科技领域枚举为空：" + file);
        }
        catalog.setDomains(catalog.getDomains());
        return catalog;
    }

    private MenuCatalog loadMenus(Path file, Path supplementalFile) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("菜单树缺失：" + file
                    + "（由 scripts/import_domain_menu_assets.py 生成）");
        }
        MenuCatalog primary = readValue(file, MenuCatalog.class);
        if (!Files.isRegularFile(supplementalFile)) {
            return primary;
        }
        return MenuCatalog.merge(primary, readValue(supplementalFile, MenuCatalog.class));
    }

    /**
     * 把已审批知识显式引用的菜单投影成 R0 导航卡。投影只补缺失卡，不覆盖人工能力卡。
     * 这样知识维护者只维护 menuId，路由和 Loop 看到的仍是标准 CapabilityCard。
     */
    private static List<CapabilityCard> projectKnowledgeMenus(List<CapabilityCard> loaded,
                                                               StandardQaBank standardQa,
                                                               MenuCatalog menus) {
        LinkedHashMap<String, CapabilityCard> cards = new LinkedHashMap<>();
        loaded.forEach(card -> cards.put(card.capabilityId(), card));

        LinkedHashMap<String, MenuCatalog.MenuEntry> referenced = new LinkedHashMap<>();
        for (StandardQaBank.Entry qa : standardQa.getItems()) {
            if (qa.getStatus() != StandardQaBank.Entry.Status.APPROVED) {
                continue;
            }
            for (String menuId : qa.getMenuOptions()) {
                MenuCatalog.MenuEntry menu = menus.find(menuId).orElseThrow(() ->
                        new IllegalStateException("标准问答 " + qa.getId()
                                + " 引用了不存在的菜单 " + menuId));
                referenced.putIfAbsent(menuId, menu);
            }
        }

        Map<String, Long> nameCounts = menus.getMenus().stream().collect(
                java.util.stream.Collectors.groupingBy(MenuCatalog.MenuEntry::getFinalName,
                        LinkedHashMap::new, java.util.stream.Collectors.counting()));
        for (MenuCatalog.MenuEntry menu : referenced.values()) {
            String capabilityId = capabilityId(menu);
            if (cards.containsKey(capabilityId)) {
                continue;
            }
            String routeName = nameCounts.getOrDefault(menu.getFinalName(), 0L) > 1
                    ? contextualName(menu) : menu.getFinalName();
            List<String> utterances = List.of(
                    "打开" + routeName,
                    "进入" + routeName,
                    "去" + routeName);
            CapabilityCard card = new CapabilityCard(
                    capabilityId,
                    "打开" + menu.getFinalName(),
                    Enums.CapabilityType.TOOL,
                    Enums.Granularity.TOOL,
                    "agent.finance_assistant",
                    List.of(menu.getTechDomain()),
                    "打开手机银行菜单「" + menu.getFinalName()
                            + "」并跳转到对应页面，本能力不涉及业务办理或交易提交",
                    utterances,
                    Map.of(), Map.of(), List.of(), List.of(),
                    com.huawei.finance.contracts.model.RiskLevel.R0,
                    2000,
                    Enums.Idempotency.SUPPORTED,
                    "入口领域",
                    "screenshot-menu-1.0.0",
                    Enums.CapabilityStatus.ACTIVE,
                    utterances,
                    List.of(menu.getFinalName(), "打开", "菜单"),
                    List.of(),
                    Enums.GuardrailOwner.DOMAIN,
                    Boolean.FALSE,
                    com.huawei.finance.contracts.model.ConfirmationPolicy.NONE,
                    com.huawei.finance.contracts.model.LoopAccess.DEFAULT,
                    Boolean.TRUE,
                    Enums.ImplementationStatus.IMPLEMENTED,
                    List.of("打开" + routeName, "进入" + routeName),
                    List.of("不办理交易", "不提交申请"),
                    List.of());
            cards.put(capabilityId, card);
        }
        List<CapabilityCard> projected = List.copyOf(cards.values());
        assertNoDuplicateCapabilityIds(projected);
        assertParentsExist(projected);
        return projected;
    }

    public static String capabilityId(MenuCatalog.MenuEntry menu) {
        return "cap.nav." + menu.getTechDomain() + "_" + menu.getFinalName();
    }

    public static String routeQuery(MenuCatalog menus, MenuCatalog.MenuEntry menu) {
        long sameName = menus.getMenus().stream()
                .filter(candidate -> menu.getFinalName().equals(candidate.getFinalName()))
                .count();
        return "打开" + (sameName > 1 ? contextualName(menu) : menu.getFinalName());
    }

    private static String contextualName(MenuCatalog.MenuEntry menu) {
        String path = menu.getPath() == null ? "" : menu.getPath()
                .replace("＞", "").replace(">", "").replace("/", "");
        return path.isBlank() ? menu.getFinalName() : path;
    }

    private List<CapabilityCard> loadCapabilitiesFromRoots(List<Path> roots, TechDomainCatalog techDomains) {
        List<CapabilityCard> all = new ArrayList<>();
        for (Path root : roots) {
            Path caps = root.resolve("capabilities");
            if (!Files.isDirectory(caps)) {
                continue;
            }
            for (Path file : listYamlRecursive(caps)) {
                JsonNode array = readYaml(file);
                if (!array.isArray()) {
                    throw new IllegalStateException("能力卡文件应为数组：" + file);
                }
                for (JsonNode node : array) {
                    ValidationOutcome outcome = validator.validate(SchemaRef.CAPABILITY_CARD, node);
                    if (!outcome.valid()) {
                        throw new IllegalStateException("能力卡不合契约 file=" + file.getFileName()
                                + " id=" + node.path("capabilityId").asText() + " 原因=" + outcome.summary());
                    }
                    validateEmbeddedSchema(file, node, "inputSchema");
                    validateEmbeddedSchema(file, node, "outputSchema");
                    all.add(canonicalizeDomains(convert(node, CapabilityCard.class), techDomains));
                }
            }
        }
        assertNoDuplicateCapabilityIds(all);
        assertParentsExist(all);
        return all;
    }

    private void validateEmbeddedSchema(Path file, JsonNode card, String field) {
        if (!card.has(field) || card.path(field).isEmpty()) return;
        Map<String, Object> schema = ContractJson.mapper().convertValue(card.path(field), Map.class);
        ValidationOutcome outcome = validator.validateSchemaDefinition(schema);
        if (!outcome.valid()) {
            throw new IllegalStateException("能力卡内嵌 Schema 非法 file=" + file.getFileName()
                    + " id=" + card.path("capabilityId").asText() + " field=" + field
                    + " 原因=" + outcome.summary());
        }
    }

    private static void assertNoDuplicateCapabilityIds(List<CapabilityCard> all) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (CapabilityCard card : all) {
            Integer prev = seen.put(card.capabilityId(), 1);
            if (prev != null) {
                throw new IllegalStateException("能力卡 capabilityId 重复：" + card.capabilityId());
            }
        }
    }

    /** 历史短码 → 附录 F 规范码（wealth→wealth_aggregate）。 */
    private static CapabilityCard canonicalizeDomains(CapabilityCard card, TechDomainCatalog catalog) {
        List<String> normalized = catalog.canonicalizeAll(card.domains());
        if (normalized.equals(card.domains())) {
            return card;
        }
        return new CapabilityCard(
                card.capabilityId(), card.name(), card.type(), card.granularity(),
                card.parentCapabilityId(), normalized, card.description(), card.supportedIntents(),
                card.inputSchema(), card.outputSchema(), card.preconditions(), card.sideEffects(),
                card.riskLevel(), card.timeoutMs(), card.idempotency(), card.owner(), card.version(),
                card.status(), card.utterances(), card.keywords(), card.requiredSlots(),
                card.guardrailOwner(), card.principalRequired(), card.confirmationPolicy(), card.loopAccess(),
                card.entryVisible(), card.implementationStatus(), card.positiveBoundary(),
                card.negativeBoundary(), card.fallbackCapabilityIds());
    }

    /**
     * 校验 TOOL/SKILL 声明的父 Agent 确实存在。
     *
     * <p>Schema 只能保证字段非空，保证不了指向的 Agent 真的注册过。父卡缺失时召回仍能命中，
     * 但执行期路由会找不到 Agent，报错发生在离根因最远的地方。
     */
    private static void assertParentsExist(List<CapabilityCard> all) {
        List<String> agentIds = all.stream()
                .filter(c -> c.type() == Enums.CapabilityType.AGENT)
                .map(CapabilityCard::capabilityId)
                .toList();
        for (CapabilityCard c : all) {
            if (c.type() == Enums.CapabilityType.AGENT) {
                continue;
            }
            if (!agentIds.contains(c.parentCapabilityId())) {
                throw new IllegalStateException("能力卡 " + c.capabilityId()
                        + " 的 parentCapabilityId=" + c.parentCapabilityId() + " 未注册");
            }
        }
    }

    private List<StrongRule> loadStrongRules(Path file) {
        JsonNode root = readYaml(file);
        List<StrongRule> rules = new ArrayList<>();
        for (JsonNode node : root.path("rules")) {
            rules.add(convert(node, StrongRule.class));
        }
        rules.sort(Comparator.comparingInt(StrongRule::priority));
        return rules;
    }

    private List<StrongRule> loadStrongRules(List<Path> roots, Path sharedRoot) {
        List<StrongRule> rules = new ArrayList<>();
        Map<String, Path> owners = new LinkedHashMap<>();
        for (Path root : roots) {
            Path file = root.resolve("rules/strong-rules.yaml");
            if (!Files.isRegularFile(file)) {
                if (root.equals(sharedRoot)) {
                    throw new IllegalStateException("强规则资产缺失：" + file);
                }
                continue;
            }
            for (StrongRule rule : loadStrongRules(file)) {
                Path previous = owners.putIfAbsent(rule.ruleId(), file);
                if (previous != null) {
                    throw new IllegalStateException("强规则 ruleId 重复：" + rule.ruleId()
                            + "，来源 " + previous + " 与 " + file);
                }
                rules.add(rule);
            }
        }
        rules.sort(Comparator.comparingInt(StrongRule::priority));
        return rules;
    }

    private List<NegativeRule> loadNegativeRules(Path file) {
        JsonNode root = readYaml(file);
        List<NegativeRule> rules = new ArrayList<>();
        for (JsonNode node : root.path("rules")) {
            rules.add(convert(node, NegativeRule.class));
        }
        return rules;
    }

    private Templates loadTemplates(Path dir) {
        JsonNode root = readYaml(dir.resolve("templates.yaml"));
        Map<String, TemplateDef> templates = new LinkedHashMap<>();
        for (JsonNode node : root.path("templates")) {
            String key = node.path("key").asText();
            Path file = dir.resolve(node.path("file").asText());
            String content = readString(file);
            templates.put(key, new TemplateDef(
                    key,
                    node.path("version").asText("1.0.0"),
                    Enums.ResponsePhase.valueOf(node.path("phase").asText("FINAL")),
                    content,
                    node.path("variables"),
                    node.path("fallback").asText(null)));
        }
        assertFallbacksTerminate(templates);

        Map<String, Map<String, String>> byCapability = new LinkedHashMap<>();
        JsonNode mapping = root.path("capabilityTemplates");
        mapping.fieldNames().forEachRemaining(capabilityId -> {
            Map<String, String> byPhase = new LinkedHashMap<>();
            JsonNode phases = mapping.path(capabilityId);
            phases.fieldNames().forEachRemaining(phase -> {
                String templateKey = phases.path(phase).asText();
                if (!templates.containsKey(templateKey)) {
                    throw new IllegalStateException("能力 " + capabilityId + " 的 " + phase
                            + " 阶段指向了不存在的模板：" + templateKey);
                }
                byPhase.put(phase, templateKey);
            });
            byCapability.put(capabilityId, byPhase);
        });

        return new Templates(templates, byCapability);
    }

    private record Templates(Map<String, TemplateDef> defs, Map<String, Map<String, String>> byCapability) {
    }

    private static void assertFallbacksTerminate(Map<String, TemplateDef> templates) {
        for (TemplateDef def : templates.values()) {
            String cursor = def.fallbackKey();
            int hops = 0;
            while (cursor != null && !cursor.isBlank()) {
                if (++hops > templates.size()) {
                    throw new IllegalStateException("模板兜底链成环，起点：" + def.key());
                }
                TemplateDef next = templates.get(cursor);
                if (next == null) {
                    throw new IllegalStateException("模板 " + cursor + " 不存在，被 " + def.key() + " 引用为兜底");
                }
                cursor = next.fallbackKey();
            }
        }
    }

    /**
     * 跨多根计算摘要；每条路径带稳定前缀，避免不同 Agent 下同名相对路径互相覆盖。
     */
    private static String digestOf(List<Path> roots) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path root : roots) {
                Path abs = root.toAbsolutePath().normalize();
                String prefix = digestPrefix(abs);
                if (!Files.isDirectory(abs)) {
                    continue;
                }
                try (Stream<Path> paths = Files.walk(abs)) {
                    List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
                    for (Path p : files) {
                        md.update(prefix.getBytes(StandardCharsets.UTF_8));
                        md.update(abs.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                        md.update(Files.readAllBytes(p));
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        } catch (IOException e) {
            throw new UncheckedIOException("计算资产摘要失败", e);
        }
    }

    private static String digestPrefix(Path absRoot) {
        // .../agents/<id>/assets → agent:<id>:
        if ("assets".equals(String.valueOf(absRoot.getFileName()))) {
            Path agentDir = absRoot.getParent();
            Path agentsParent = agentDir == null ? null : agentDir.getParent();
            if (agentsParent != null && "agents".equals(String.valueOf(agentsParent.getFileName()))) {
                return "agent:" + agentDir.getFileName() + ":";
            }
        }
        return "shared:";
    }

    private JsonNode readYaml(Path file) {
        try {
            return yaml.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取资产失败：" + file, e);
        }
    }

    private <T> T readValue(Path file, Class<T> type) {
        try {
            return yaml.readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            throw new UncheckedIOException("解析资产失败：" + file, e);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        return yaml.convertValue(node, type);
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取模板失败：" + file, e);
        }
    }

    /** 能力卡目录含子目录（agents/、nav/）。 */
    private static List<Path> listYamlRecursive(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列举能力卡目录失败：" + dir, e);
        }
    }
}
