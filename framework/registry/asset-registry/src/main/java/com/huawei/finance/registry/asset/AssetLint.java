package com.huawei.finance.registry.asset;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SlotNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资产发布门禁（FP-45）。
 *
 * <p>与加载期的 Schema 校验分工明确：Schema 管「字段在不在、类型对不对」，这里管
 * 「配得合不合理」。后者拦得住的都是不会让任何用例变红的那类问题——一张 description 写成
 * 「查询相关服务」的卡不违反任何 Schema，它只是让召回长期偏低，然后在标定阈值时被误当成
 * 「模型不够好」。
 *
 * <p><b>刻意不在加载期执行</b>。这是发布门禁（CI + MR），不是启动门禁。理由是行内自己的资产
 * 目录会先有一批不合本规则的历史卡，让应用因为一句描述写得不好而起不来，这条规则的第一个
 * 结果就是被人注释掉。Schema 那一层才是启动门禁——不合契约的卡进不了内存。
 *
 * <p>输出分 {@code ERROR} 与 {@code WARN}。CI 只对 ERROR 拒绝合并；WARN 是给评审人看的，
 * 判定「这条到底算不算问题」需要业务上下文，机器给不出。
 */
public final class AssetLint {

    /** 单条发现。{@code where} 定位到能力 id 或文件，供 MR 评论直接引用。 */
    public record Finding(Severity severity, String rule, String where, String detail) {
        @Override
        public String toString() {
            return severity + " [" + rule + "] " + where + "：" + detail;
        }
    }

    public enum Severity {
        ERROR, WARN
    }

    /**
     * 参与召回的能力卡，其 description 必须写出负向边界。
     *
     * <p>§2.7.8 的教训：外部实现在能力描述末句写了「本工具不涉及用户个人信息查询」，
     * 这一句真的守住了工具选择那一层（失守发生在绕过它的兜底链上）。负向边界是召回与仲裁
     * 唯一能读到的「我不管什么」，没有它，模型只能靠正向描述的相似度去猜边界在哪。
     */
    private static final List<String> NEGATIVE_BOUNDARY_MARKERS =
            List.of("不涉及", "不支持", "不包含", "不处理", "不用于", "不适用");

    /** 标准答案里的变量占位，与 FreeMarker 的写法保持一致。 */
    private static final Pattern ANSWER_VARIABLE = Pattern.compile("\\$\\{\\s*([A-Za-z0-9_]+)\\s*}");

    private static final int MIN_DESCRIPTION_LENGTH = 10;
    private static final int MIN_UTTERANCES = 3;

    private AssetLint() {
    }

    public static List<Finding> inspect(AssetBundle bundle) {
        List<Finding> findings = new ArrayList<>();
        List<CapabilityCard> cards = bundle.capabilities();

        checkDuplicateIds(cards, findings);
        for (CapabilityCard card : cards) {
            checkDescription(card, findings);
            checkRecallText(card, findings);
            checkSlotNames(card, findings);
            checkClarifyCoverage(card, bundle.clarify(), findings);
            checkDomains(card, bundle.techDomains(), findings);
        }
        checkUtteranceOverlap(cards, findings);
        checkTemplateCoverage(cards, bundle, findings);
        checkStandardQa(bundle, findings);
        checkMenuTechDomains(bundle, findings);
        checkNegativeSuppressTargets(bundle, findings);
        checkKnowledgeSupplements(bundle, findings);

        return List.copyOf(findings);
    }

    private static void checkKnowledgeSupplements(AssetBundle bundle, List<Finding> findings) {
        checkKnowledgeSkill("arbitration", bundle.arbitrationSkill(), findings);
        checkKnowledgeSkill("context-rewrite", bundle.contextRewriteSkill(), findings);
        checkKnowledgeSkill("continuation", bundle.continuationSkill(), findings);
        checkKnowledgeSkill("loop-planner", bundle.loopPlannerSkill(), findings);
    }

    private static void checkKnowledgeSkill(String skillName, ArbitrationSkill skill,
                                            List<Finding> findings) {
        if (skill == null) return;
        int index = 0;
        for (Map<String, Object> example : skill.getExamples()) {
            List<String> errors = ArbitrationSkill.knowledgeAdmissionErrors(example);
            if (!errors.isEmpty()) {
                String name = example == null ? "null" : String.valueOf(example.getOrDefault("name", index));
                findings.add(new Finding(Severity.ERROR, "KNOWLEDGE_SUPPLEMENT_NOT_ADMITTED",
                        skillName + ":" + name, String.join("; ", errors)));
            }
            index++;
        }
    }

    /**
     * 负向规则的 {@code suppress} 必须指向真实存在的能力。
     *
     * <p>这一条补的是与 FP-17 同源的缺口：那条用例验的是打压**效果**，因为「规则能加载、
     * 条数对得上」验的只是 YAML 解析器。但效果用例只覆盖它自己举的那几个能力；
     * 一条 {@code suppress} 把能力 ID 敲错，加载成功、条数对得上、效果用例照旧全绿，
     * 而那条规则从此永不生效——没有任何迹象。
     *
     * <p>类通配（{@code cap.nav.*}）要求至少展开到一个能力。写了通配却一个都不匹配，
     * 通常意味着前缀敲错或那批卡还没入库，同样是「规则静默失效」。
     */
    private static void checkNegativeSuppressTargets(AssetBundle bundle, List<Finding> findings) {
        java.util.Set<String> known = bundle.capabilities().stream()
                .map(CapabilityCard::capabilityId)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        for (NegativeRule rule : bundle.negativeRules()) {
            for (String target : rule.suppress()) {
                if (target == null || target.isBlank()) {
                    findings.add(new Finding(Severity.ERROR, "EMPTY_SUPPRESS_TARGET", rule.ruleId(),
                            "suppress 含空条目"));
                    continue;
                }
                if (target.endsWith("*")) {
                    String prefix = target.substring(0, target.length() - 1);
                    if (known.stream().noneMatch(id -> id.startsWith(prefix))) {
                        findings.add(new Finding(Severity.ERROR, "SUPPRESS_CLASS_MATCHES_NOTHING",
                                rule.ruleId(), "suppress 通配「" + target + "」未匹配到任何能力，规则将静默失效"));
                    }
                } else if (!known.contains(target)) {
                    findings.add(new Finding(Severity.ERROR, "UNKNOWN_SUPPRESS_TARGET", rule.ruleId(),
                            "suppress 指向不存在的能力「" + target + "」，规则将静默失效"));
                }
            }
        }
    }

    /** 能力卡 domains 必须是附录 F 规范码（加载期已做短码别名）。 */
    private static void checkDomains(CapabilityCard card, TechDomainCatalog catalog,
                                     List<Finding> findings) {
        if (catalog == null || catalog.getDomains().isEmpty()) {
            findings.add(new Finding(Severity.ERROR, "NO_TECH_DOMAIN_CATALOG", card.capabilityId(),
                    "科技领域枚举未加载，无法校验 domains"));
            return;
        }
        for (String domain : card.domains()) {
            if (!catalog.isKnown(domain)) {
                findings.add(new Finding(Severity.ERROR, "UNKNOWN_DOMAIN", card.capabilityId(),
                        "domains 含未注册科技域码 「" + domain + "」。已知："
                                + new java.util.TreeSet<>(catalog.codes())
                                + "。历史短码请改规范码或写入 tech-domains.yaml aliases"));
            }
        }
    }

    /** 菜单树 techDomain 若为 unmapped 只 WARN——不阻断启动，留给运营补映射。 */
    private static void checkMenuTechDomains(AssetBundle bundle, List<Finding> findings) {
        MenuCatalog menus = bundle.menus();
        if (menus == null) {
            return;
        }
        long unmapped = menus.getMenus().stream()
                .filter(m -> "unmapped".equals(m.getTechDomain()))
                .count();
        if (unmapped > 0) {
            findings.add(new Finding(Severity.WARN, "MENU_UNMAPPED_TECH", "menu-tree",
                    unmapped + " 条菜单未能归到科技域（techDomain=unmapped）"));
        }
    }

    /** ERROR 才拦合并。WARN 交给评审人，机器判不了「这句描述算不算写清楚了」。 */
    public static List<Finding> errors(AssetBundle bundle) {
        return inspect(bundle).stream().filter(f -> f.severity() == Severity.ERROR).toList();
    }

    /**
     * 标准问答库（FP-1I）。
     *
     * <p>这一类条目的危险性高于能力卡：命中即直接念答案，既不召回也不过模型，
     * 一条写歪的模版不会让任何用例变红，只会让机器人对着一大片问题念同一段话。
     */
    private static void checkStandardQa(AssetBundle bundle, List<Finding> findings) {
        List<StandardQaBank.Entry> items = bundle.standardQa().getItems();
        Set<String> ids = new LinkedHashSet<>();
        List<StandardQaBank.Entry> earlier = new ArrayList<>();

        for (StandardQaBank.Entry entry : items) {
            String where = entry.getId().isBlank() ? "(无 id)" : entry.getId();

            if (!ids.add(entry.getId())) {
                findings.add(new Finding(Severity.ERROR, "QA_DUPLICATE_ID", where,
                        "标准问答 id 重复。运营台上两条会长得一模一样，改错一条无从察觉"));
            }
            if (!entry.isBlocked() && entry.getAnswer().isBlank()) {
                findings.add(new Finding(Severity.ERROR, "QA_EMPTY_ANSWER", where,
                        "没有标准答案。这样的条目永不命中，等于一条看得见却不生效的配置"));
            }
            if (entry.isBlocked() && entry.getGuidance().isBlank()) {
                findings.add(new Finding(Severity.ERROR, "QA_BLOCKED_WITHOUT_GUIDANCE", where,
                        "来源被阻断但没有安全引导，命中后无法向用户解释下一步"));
            }
            if (entry.isBlocked() && !entry.getAnswer().isBlank()) {
                findings.add(new Finding(Severity.ERROR, "QA_BLOCKED_WITH_ANSWER", where,
                        "来源被阻断时不得配置标准答案，避免把占位或未审批内容当作业务口径"));
            }
            if (entry.getPatterns().isEmpty()) {
                findings.add(new Finding(Severity.ERROR, "QA_NO_PATTERN", where,
                        "没有句法模版，这条永远匹配不上"));
            }
            checkQaPatterns(entry, where, findings);
            checkQaAnswerSlots(entry, where, findings);

            if (entry.hasAction() && bundle.capability(entry.getActionCapabilityId()) == null) {
                findings.add(new Finding(Severity.ERROR, "QA_UNKNOWN_ACTION", where,
                        "动作入口指向不存在的能力 " + entry.getActionCapabilityId()
                                + "。用户点了会掉进空处"));
            }
            for (String menuId : entry.getMenuOptions()) {
                var menu = bundle.menus().find(menuId);
                if (menu.isEmpty()) {
                    findings.add(new Finding(Severity.ERROR, "QA_UNKNOWN_MENU", where,
                            "菜单选项指向不存在的菜单 " + menuId));
                    continue;
                }
                String capabilityId = AssetLoader.capabilityId(menu.get());
                if (bundle.capability(capabilityId) == null) {
                    findings.add(new Finding(Severity.ERROR, "QA_MENU_NOT_ROUTABLE", where,
                            "菜单 " + menuId + " 没有可路由导航卡 " + capabilityId));
                }
            }
            if (entry.isBlocked() && entry.hasAction()) {
                findings.add(new Finding(Severity.ERROR, "QA_BLOCKED_WITH_ACTION", where,
                        "来源被阻断时不得直接挂执行动作；应使用自然语言选项让用户明确选择"));
            }
            checkQaShadowing(entry, earlier, where, findings);
            earlier.add(entry);
        }
    }

    private static void checkQaPatterns(StandardQaBank.Entry entry, String where,
                                        List<Finding> findings) {
        for (String pattern : entry.getPatterns()) {
            try {
                SyntacticTemplate.compile(pattern);
            } catch (IllegalArgumentException e) {
                // 加载期对编译不了的模版是整条跳过（不让一条配置把应用拦在启动之外），
                // 所以这里必须是 ERROR：否则它就成了一条静默失效的规则
                findings.add(new Finding(Severity.ERROR, "QA_BAD_PATTERN", where, e.getMessage()));
            }
        }
    }

    /** 答案里的 {@code ${x}} 必须声明进 requiredSlots，且 x 得是真抽得出来的槽位。 */
    private static void checkQaAnswerSlots(StandardQaBank.Entry entry, String where,
                                           List<Finding> findings) {
        Matcher matcher = ANSWER_VARIABLE.matcher(entry.getAnswer());
        while (matcher.find()) {
            String slot = matcher.group(1);
            if (!entry.getRequiredSlots().contains(slot)) {
                findings.add(new Finding(Severity.ERROR, "QA_UNDECLARED_SLOT", where,
                        "答案里用了 ${" + slot + "} 却没写进 requiredSlots。"
                                + "缺这一句，抽不到值时会念出一句带空洞的话"));
            }
            if (!SlotNames.EXTRACTABLE.contains(slot)) {
                findings.add(new Finding(Severity.ERROR, "QA_UNKNOWN_SLOT", where,
                        "槽位 " + slot + " 不在可抽取清单里，永远取不到值"));
            }
        }
    }

    /**
     * 前一条的模版把后一条的标准问吃掉了。
     *
     * <p>匹配按顺序取第一命中，所以被吃掉的那条永远不会生效——它在运营台上看着好好的，
     * 改它也不会有任何效果，这是最难查的一类配置故障。
     */
    private static void checkQaShadowing(StandardQaBank.Entry entry, List<StandardQaBank.Entry> earlier,
                                         String where, List<Finding> findings) {
        if (entry.getQuestion().isBlank()) {
            return;
        }
        for (StandardQaBank.Entry previous : earlier) {
            for (SyntacticTemplate template : previous.compiled()) {
                if (template.matches(entry.getQuestion())) {
                    findings.add(new Finding(Severity.ERROR, "QA_SHADOWED", where,
                            "标准问「" + entry.getQuestion() + "」会先被 " + previous.getId()
                                    + " 的模版「" + template.source() + "」吃掉，本条永不生效"));
                    return;
                }
            }
        }
    }

    private static void checkDuplicateIds(List<CapabilityCard> cards, List<Finding> findings) {
        Set<String> seen = new LinkedHashSet<>();
        for (CapabilityCard card : cards) {
            if (!seen.add(card.capabilityId())) {
                findings.add(new Finding(Severity.ERROR, "DUPLICATE_ID", card.capabilityId(),
                        "同一个 capabilityId 出现了两次。后加载的会静默覆盖先加载的，"
                                + "而两张卡的 riskLevel 与 requiredSlots 可能完全不同"));
            }
        }
    }

    private static void checkDescription(CapabilityCard card, List<Finding> findings) {
        String description = card.description() == null ? "" : card.description().trim();
        if (description.length() < MIN_DESCRIPTION_LENGTH) {
            findings.add(new Finding(Severity.ERROR, "DESCRIPTION_TOO_SHORT", card.capabilityId(),
                    "description 只有 " + description.length() + " 字。它同时是 embedding 的文档侧文本"
                            + "与仲裁 prompt 里的候选说明，写不清就是两条通道一起变差"));
            return;
        }
        if (isRecallable(card) && NEGATIVE_BOUNDARY_MARKERS.stream().noneMatch(description::contains)) {
            findings.add(new Finding(Severity.ERROR, "MISSING_NEGATIVE_BOUNDARY", card.capabilityId(),
                    "description 没有负向边界。参与召回的能力必须写清自己不管什么"
                            + "（如「本能力不涉及他人账户」），否则召回与仲裁只能靠正向相似度猜边界"));
        }
    }

    private static void checkRecallText(CapabilityCard card, List<Finding> findings) {
        if (!isRecallable(card)) {
            return;
        }
        if (card.utterances().size() < MIN_UTTERANCES) {
            findings.add(new Finding(Severity.WARN, "TOO_FEW_UTTERANCES", card.capabilityId(),
                    "只有 " + card.utterances().size() + " 条 utterance。BM25 与向量两条通道都吃它，"
                            + "样例太少时同义说法召不回来"));
        }
        if (card.keywords().isEmpty()) {
            findings.add(new Finding(Severity.WARN, "NO_KEYWORDS", card.capabilityId(),
                    "keywords 为空，分词驱动的 terms 通道对这张卡不会产生任何命中"));
        }
    }

    /**
     * 槽位名漂移（§2.7.3 第 3 项的成因）。
     *
     * <p>能力卡声明了一个抽槽器产不出的名字时，主 Agent 永远填不上它。对 R2 能力，
     * 缺槽即拒绝执行，用户拿到的是一个每轮都在问同一句话的死循环；而这条链路上的每一环
     * 都合法：Schema 过、卡加载成功、澄清也确实在问。
     */
    private static void checkSlotNames(CapabilityCard card, List<Finding> findings) {
        for (String slot : card.requiredSlots()) {
            if (!SlotNames.EXTRACTABLE.contains(slot)) {
                findings.add(new Finding(Severity.ERROR, "UNKNOWN_SLOT", card.capabilityId(),
                        "requiredSlots 声明了 " + slot + "，但主 Agent 抽不出这个名字。"
                                + "已知可抽取：" + new java.util.TreeSet<>(SlotNames.EXTRACTABLE)
                                + "。加新槽位的顺序是 SlotNames → 抽槽器 → 澄清话术 → 能力卡"));
            }
        }
    }

    /**
     * 澄清话术覆盖。
     *
     * <p>没配话术的槽位缺失时，回复层只能落到 {@code tpl.fallback.generic}——用户被拒了，
     * 却不知道该补什么。宁可用兜底也不临时拼一句「请提供 payee」，所以缺话术不会报错、
     * 只会让这个能力实际上办不成。
     */
    private static void checkClarifyCoverage(CapabilityCard card, ClarifyConfig clarify,
                                             List<Finding> findings) {
        if (clarify == null) {
            return;
        }
        for (String slot : card.requiredSlots()) {
            if (!clarify.getSlots().containsKey(slot)) {
                findings.add(new Finding(Severity.ERROR, "NO_CLARIFY_QUESTION", card.capabilityId(),
                        "requiredSlots 里的 " + slot + " 在 clarify.yaml 里没有问法。"
                                + "缺它时用户只会拿到一句通用兜底，不知道该补什么"));
            }
        }
    }

    /**
     * 语义重叠。
     *
     * <p>两张卡挂着同一条 utterance 时，检索必然给出相近的分，而 Top1 与 Top2 分不出高下
     * 就是 LOW_MARGIN——本该直出的请求被推去澄清或慢路径。这类冲突在单张卡的评审里看不出来，
     * 只有把全集放在一起比才能发现。
     */
    private static void checkUtteranceOverlap(List<CapabilityCard> cards, List<Finding> findings) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (CapabilityCard card : cards) {
            if (!isRecallable(card)) {
                continue;
            }
            for (String utterance : card.utterances()) {
                owners.computeIfAbsent(utterance.trim(), k -> new ArrayList<>()).add(card.capabilityId());
            }
        }
        owners.forEach((utterance, ids) -> {
            if (ids.size() > 1) {
                findings.add(new Finding(Severity.ERROR, "UTTERANCE_COLLISION", String.join(" / ", ids),
                        "这几张卡挂了同一条 utterance「" + utterance + "」。检索必然给出相近的分，"
                                + "Top1 与 Top2 分不出高下就是 LOW_MARGIN，本该直出的请求会被推去澄清"));
            }
        });
    }

    /**
     * 模板覆盖。
     *
     * <p>加载期已经校验「模板映射指向的模板存在」，但没有校验反向——一张能执行的卡
     * 完全没有配模板映射时，它的成功结果会用通用兜底话术回复。那句话是合法输出，
     * 线上看不出异常，只是用户永远拿不到自己要的那个数字。
     */
    private static void checkTemplateCoverage(List<CapabilityCard> cards, AssetBundle bundle,
                                              List<Finding> findings) {
        for (CapabilityCard card : cards) {
            if (!isRecallable(card)) {
                continue;
            }
            if (bundle.templateKeyFor(card.capabilityId(), "FINAL") == null) {
                findings.add(new Finding(Severity.WARN, "NO_FINAL_TEMPLATE", card.capabilityId(),
                        "没有 FINAL 阶段模板，执行成功后会用通用兜底话术回复——"
                                + "那是一句合法输出，线上看不出异常"));
            }
        }
    }

    /** AGENT 粒度不直接执行也不参与召回，检索侧的要求对它不适用。 */
    private static boolean isRecallable(CapabilityCard card) {
        return card.type() != Enums.CapabilityType.AGENT
                && card.status() != Enums.CapabilityStatus.DISABLED
                && Boolean.TRUE.equals(card.entryVisible());
    }
}
