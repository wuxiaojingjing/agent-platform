package com.huawei.finance.registry.asset;

import com.huawei.finance.contracts.model.CapabilityCard;
import java.util.List;
import java.util.Map;

/**
 * 一次加载得到的完整资产集合。
 *
 * <p>{@code assetVersion} 是「人工版本号 + 内容摘要」的组合，例如
 * {@code assets-v1.0.0+a3f91c2b}。只用人工版本号是不够的：改了同义词表却忘了升版本，
 * 出口缓存会继续返回改版前的结论，而这种脏读在测试环境几乎不可能复现。
 * 摘要参与后，内容一变缓存键必变，忘记升版本最多是版本号不好看，不会出错。
 *
 * @param assetVersion   资产版本（人工版本 + 内容摘要）
 * @param declaredVersion 清单里声明的人工版本
 * @param capabilities   能力卡
 * @param strongRules    强规则，已按 priority 升序
 * @param negativeRules  负向规则
 * @param fusion         融合权重与阈值
 * @param synonyms       改写词表
 * @param clarify        澄清话术
 * @param templates      模板注册表
 * @param capabilityTemplates 能力 → 阶段 → 模板键
 * @param arbitrationSkill 仲裁提示词
 * @param complianceTopics 按话题触发的合规提示清单，与能力风险等级并列的那条通道
 * @param standardQa       标准问答库，句法模版命中即直接念答案（FP-1I）
 * @param techDomains      科技领域枚举（附录 F）
 * @param menus            菜单树
 */
public record AssetBundle(
        String assetVersion,
        String declaredVersion,
        List<CapabilityCard> capabilities,
        List<StrongRule> strongRules,
        List<NegativeRule> negativeRules,
        FusionConfig fusion,
        SynonymTable synonyms,
        ClarifyConfig clarify,
        Map<String, TemplateDef> templates,
        Map<String, Map<String, String>> capabilityTemplates,
        ArbitrationSkill arbitrationSkill,
        ArbitrationSkill contextRewriteSkill,
        ArbitrationSkill continuationSkill,
        ArbitrationSkill loopPlannerSkill,
        ComplianceTopics complianceTopics,
        StandardQaBank standardQa,
        TechDomainCatalog techDomains,
        MenuCatalog menus,
        ResponsePolicy responsePolicy,
        ProductCatalog productCatalog,
        ProductComparisonPolicy productComparisonPolicy) {

    public AssetBundle {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        strongRules = strongRules == null ? List.of() : List.copyOf(strongRules);
        negativeRules = negativeRules == null ? List.of() : List.copyOf(negativeRules);
        templates = templates == null ? Map.of() : Map.copyOf(templates);
        capabilityTemplates = capabilityTemplates == null ? Map.of() : Map.copyOf(capabilityTemplates);
        complianceTopics = complianceTopics == null ? new ComplianceTopics() : complianceTopics;
        standardQa = standardQa == null ? new StandardQaBank() : standardQa;
        techDomains = techDomains == null ? TechDomainCatalog.empty() : techDomains;
        menus = menus == null ? MenuCatalog.empty() : menus;
        continuationSkill = continuationSkill == null ? new ArbitrationSkill() : continuationSkill;
        contextRewriteSkill = contextRewriteSkill == null ? new ArbitrationSkill() : contextRewriteSkill;
        loopPlannerSkill = loopPlannerSkill == null ? new ArbitrationSkill() : loopPlannerSkill;
        responsePolicy = responsePolicy == null ? new ResponsePolicy() : responsePolicy;
        productCatalog = productCatalog == null ? new ProductCatalog() : productCatalog;
        productComparisonPolicy = productComparisonPolicy == null
                ? new ProductComparisonPolicy() : productComparisonPolicy;
    }

    public AssetBundle(String assetVersion, String declaredVersion, List<CapabilityCard> capabilities,
                       List<StrongRule> strongRules, List<NegativeRule> negativeRules,
                       FusionConfig fusion, SynonymTable synonyms, ClarifyConfig clarify,
                       Map<String, TemplateDef> templates,
                       Map<String, Map<String, String>> capabilityTemplates,
                       ArbitrationSkill arbitrationSkill, ComplianceTopics complianceTopics,
                       StandardQaBank standardQa, TechDomainCatalog techDomains, MenuCatalog menus) {
        this(assetVersion, declaredVersion, capabilities, strongRules, negativeRules, fusion,
                synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                null, null, null, complianceTopics, standardQa, techDomains, menus, null, null, null);
    }

    /** Compatibility constructor for bundles created before contextual rewrite became a separate skill. */
    public AssetBundle(String assetVersion, String declaredVersion, List<CapabilityCard> capabilities,
                       List<StrongRule> strongRules, List<NegativeRule> negativeRules,
                       FusionConfig fusion, SynonymTable synonyms, ClarifyConfig clarify,
                       Map<String, TemplateDef> templates,
                       Map<String, Map<String, String>> capabilityTemplates,
                       ArbitrationSkill arbitrationSkill, ArbitrationSkill continuationSkill,
                       ArbitrationSkill loopPlannerSkill, ComplianceTopics complianceTopics,
                       StandardQaBank standardQa, TechDomainCatalog techDomains, MenuCatalog menus) {
        this(assetVersion, declaredVersion, capabilities, strongRules, negativeRules, fusion,
                synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                null, continuationSkill, loopPlannerSkill, complianceTopics, standardQa,
                techDomains, menus, null, null, null);
    }

    /** 测试与局部替换用：沿用真实资产的域/菜单表。 */
    public AssetBundle withCapabilities(List<CapabilityCard> cards) {
        return new AssetBundle(assetVersion, declaredVersion, cards, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill, complianceTopics, standardQa, techDomains, menus,
                responsePolicy, productCatalog, productComparisonPolicy);
    }

    public AssetBundle withStandardQa(StandardQaBank bank) {
        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill, complianceTopics, bank, techDomains, menus,
                responsePolicy, productCatalog, productComparisonPolicy);
    }

    public AssetBundle withContextRewriteSkill(ArbitrationSkill skill) {
        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                skill, continuationSkill, loopPlannerSkill, complianceTopics, standardQa, techDomains, menus,
                responsePolicy, productCatalog, productComparisonPolicy);
    }

    public AssetBundle withArbitrationSkill(ArbitrationSkill skill) {
        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, skill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill, complianceTopics, standardQa,
                techDomains, menus, responsePolicy, productCatalog, productComparisonPolicy);
    }

    public AssetBundle withContinuationSkill(ArbitrationSkill skill) {
        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                contextRewriteSkill, skill, loopPlannerSkill, complianceTopics, standardQa, techDomains, menus,
                responsePolicy, productCatalog, productComparisonPolicy);
    }

    public AssetBundle withComplianceTopics(ComplianceTopics topics) {
        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill, topics, standardQa, techDomains, menus,
                responsePolicy, productCatalog, productComparisonPolicy);
    }

    /** Test/local snapshot replacement; production still publishes the complete bundle atomically. */
    public AssetBundle withResponsePolicy(ResponsePolicy policy) {
        return new AssetBundle(assetVersion, declaredVersion, capabilities, strongRules, negativeRules,
                fusion, synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill, complianceTopics, standardQa,
                techDomains, menus, policy, productCatalog, productComparisonPolicy);
    }

    /** Compatibility constructor for callers created before product identity assets were added. */
    public AssetBundle(String assetVersion, String declaredVersion, List<CapabilityCard> capabilities,
                       List<StrongRule> strongRules, List<NegativeRule> negativeRules,
                       FusionConfig fusion, SynonymTable synonyms, ClarifyConfig clarify,
                       Map<String, TemplateDef> templates,
                       Map<String, Map<String, String>> capabilityTemplates,
                       ArbitrationSkill arbitrationSkill, ArbitrationSkill contextRewriteSkill,
                       ArbitrationSkill continuationSkill, ArbitrationSkill loopPlannerSkill,
                       ComplianceTopics complianceTopics, StandardQaBank standardQa,
                       TechDomainCatalog techDomains, MenuCatalog menus,
                       ResponsePolicy responsePolicy) {
        this(assetVersion, declaredVersion, capabilities, strongRules, negativeRules, fusion,
                synonyms, clarify, templates, capabilityTemplates, arbitrationSkill,
                contextRewriteSkill, continuationSkill, loopPlannerSkill, complianceTopics,
                standardQa, techDomains, menus, responsePolicy, null, null);
    }

    /** 查能力在某阶段的模板键，未配置返回 null，由回复层走兜底。 */
    public String templateKeyFor(String capabilityId, String phase) {
        return capabilityTemplates.getOrDefault(capabilityId, Map.of()).get(phase);
    }

    public CapabilityCard capability(String capabilityId) {
        for (CapabilityCard c : capabilities) {
            if (c.capabilityId().equals(capabilityId)) {
                return c;
            }
        }
        return null;
    }

    /** 参与召回的能力卡：AGENT 粒度不直接执行，DISABLED 不应出现在候选里。 */
    public List<CapabilityCard> recallableCapabilities() {
        return capabilities.stream()
                .filter(c -> c.type() != com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT)
                .filter(c -> c.status() != com.huawei.finance.contracts.model.Enums.CapabilityStatus.DISABLED)
                .filter(c -> Boolean.TRUE.equals(c.entryVisible()))
                .toList();
    }

    /**
     * 入口域路由用的 AGENT 卡（架构草案 v0.4 §13 阶段 1.5）。
     *
     * <p>与 {@link #recallableCapabilities()} 分工：TOOL 仍供域内/过渡期快路径召回；
     * AGENT 卡描述「投给哪个科技域节点」，不直接执行。两套候选不得混用同一套阈值（§12 第 21 条）。
     */
    public List<CapabilityCard> domainRoutingCapabilities() {
        return capabilities.stream()
                .filter(c -> c.type() == com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT)
                .filter(c -> c.status() != com.huawei.finance.contracts.model.Enums.CapabilityStatus.DISABLED)
                .toList();
    }
}
