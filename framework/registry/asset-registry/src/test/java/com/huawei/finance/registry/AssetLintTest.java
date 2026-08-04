package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLint;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.ArbitrationSkill;
import com.huawei.finance.registry.asset.StandardQaBank;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-45 资产发布门禁。
 *
 * <p>判定原文要求「一张劣质能力卡的 MR 被 CI 拒绝并给出原因」。因此这一组用例的形态是：
 * 每一类问题各造一张卡，断言它被拒、且拒绝理由能定位到卡与规则——只断言"被拒了"不够，
 * MR 上收到一句「资产校验失败」的评审人还是不知道要改哪一行。
 *
 * <p>第一条用例是反向的：**在库的这批资产必须零 ERROR**。少了它，这套规则可以永远绿着，
 * 因为没人会去跑一张不存在的劣质卡。
 */
class AssetLintTest {

    private static AssetBundle real;

    @BeforeAll
    static void load() {
        real = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    @Test
    @DisplayName("在库资产零 ERROR：规则必须先能被现有资产满足，否则它只会被注释掉")
    void shippedAssetsAreClean() {
        List<AssetLint.Finding> errors = AssetLint.errors(real);
        assertThat(errors).as("发现：%s", errors).isEmpty();
    }

    @Test
    @DisplayName("内部支撑能力可被精确寻址，但不会进入用户入口召回")
    void internalSupportCapabilityIsNotRecallable() {
        CapabilityCard resolver = real.capability("cap.account.reference.resolve");

        assertThat(resolver).isNotNull();
        assertThat(resolver.entryVisible()).isFalse();
        assertThat(real.recallableCapabilities())
                .extracting(CapabilityCard::capabilityId)
                .doesNotContain(resolver.capabilityId());
    }

    @Test
    @DisplayName("知识补强宣称已准入却没有负例回归时拒绝发布")
    void evaluatedGapWithoutCompleteEvidenceIsRejected() {
        ArbitrationSkill skill = new ArbitrationSkill();
        Map<String, Object> incomplete = admittedKnowledge("missing-negative");
        incomplete.put("negativeRegressionCaseIds", List.of());
        skill.setExamples(List.of(incomplete));

        assertThat(AssetLint.errors(real.withContextRewriteSkill(skill))).anySatisfy(finding -> {
            assertThat(finding.rule()).isEqualTo("KNOWLEDGE_SUPPLEMENT_NOT_ADMITTED");
            assertThat(finding.where()).isEqualTo("context-rewrite:missing-negative");
            assertThat(finding.detail()).contains("negativeRegressionCaseIds");
        });
    }

    @Test
    @DisplayName("知识补强的未知激活态拒绝发布")
    void unknownKnowledgeActivationIsRejected() {
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setExamples(List.of(Map.of(
                "name", "unknown",
                "activation", "AUTO",
                "content", "不得自动上线")));

        assertThat(AssetLint.errors(real.withContextRewriteSkill(skill))).anySatisfy(finding -> {
            assertThat(finding.rule()).isEqualTo("KNOWLEDGE_SUPPLEMENT_NOT_ADMITTED");
            assertThat(finding.detail()).contains("DRAFT or EVALUATED_GAP");
        });
    }

    @Test
    @DisplayName("证据和正反回归齐全的知识补强允许发布")
    void evaluatedGapWithCompleteEvidenceIsAccepted() {
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setExamples(List.of(admittedKnowledge("admitted")));

        assertThat(AssetLint.errors(real.withContextRewriteSkill(skill)))
                .noneMatch(finding -> finding.rule().equals("KNOWLEDGE_SUPPLEMENT_NOT_ADMITTED"));
    }

    @Test
    @DisplayName("非模型语义故障、非领域知识根因或单次失败均拒绝作为知识发布")
    void onlyStableHealthyModelDomainKnowledgeGapsCanBePublished() {
        ArbitrationSkill skill = new ArbitrationSkill();
        Map<String, Object> infrastructure = admittedKnowledge("infrastructure");
        infrastructure.put("failureCategory", "INFRA_UNAVAILABLE");
        Map<String, Object> modelCapability = admittedKnowledge("model-capability");
        modelCapability.put("gapType", "MODEL_CAPABILITY_GAP");
        Map<String, Object> oneOff = admittedKnowledge("one-off");
        oneOff.put("failedParaphraseCaseIds", List.of("only-one"));
        skill.setExamples(List.of(infrastructure, modelCapability, oneOff));

        assertThat(AssetLint.errors(real.withContextRewriteSkill(skill)))
                .filteredOn(finding -> finding.rule().equals("KNOWLEDGE_SUPPLEMENT_NOT_ADMITTED"))
                .hasSize(3)
                .extracting(AssetLint.Finding::detail)
                .anyMatch(detail -> detail.contains("failureCategory"))
                .anyMatch(detail -> detail.contains("gapType"))
                .anyMatch(detail -> detail.contains("failedParaphraseCaseIds"));
    }

    private static Map<String, Object> admittedKnowledge(String name) {
        Map<String, Object> example = new java.util.LinkedHashMap<>();
        example.put("name", name);
        example.put("activation", "EVALUATED_GAP");
        example.put("input", "领域表达");
        example.put("outputContract", "结构化语义");
        example.put("baselineModelHealthy", true);
        example.put("baselineModelVersion", "model-baseline-v1");
        example.put("baselinePromptVersion", "prompt-baseline-v1");
        example.put("failureCategory", ArbitrationSkill.MODEL_SEMANTIC_FAILURE);
        example.put("gapType", ArbitrationSkill.DOMAIN_KNOWLEDGE_GAP);
        example.put("failedEvaluationIds", List.of("baseline-1"));
        example.put("failedParaphraseCaseIds", List.of("paraphrase-a", "paraphrase-b"));
        example.put("positiveRegressionCaseIds", List.of("positive-1"));
        example.put("negativeRegressionCaseIds", List.of("negative-1"));
        return example;
    }

    @Test
    @DisplayName("负向边界缺失即拒绝，理由定位到卡")
    void missingNegativeBoundaryIsRejected() {
        List<AssetLint.Finding> errors = AssetLint.errors(bundleWith(
                card("cap.bad.no-boundary", "查询账户余额与账户状态信息", List.of())));

        assertThat(errors).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("MISSING_NEGATIVE_BOUNDARY");
            assertThat(f.where()).isEqualTo("cap.bad.no-boundary");
        });
    }

    @Test
    @DisplayName("AGENT 粒度豁免负向边界：它不参与召回，检索侧的要求对它不适用")
    void agentCardsAreExemptFromRecallRules() {
        CapabilityCard agent = new CapabilityCard("agent.bad", "某某助手", Enums.CapabilityType.AGENT,
                Enums.Granularity.AGENT, null, List.of("account"), "某某领域的领域智能体", List.of(),
                Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 5000,
                Enums.Idempotency.SUPPORTED, "owner", "1.0.0", Enums.CapabilityStatus.ACTIVE,
                List.of(), List.of(), List.of(), null);

        assertThat(AssetLint.errors(bundleWith(agent))).isEmpty();
    }

    /**
     * §2.7.3 第 3 项的那个外部实例：NLU 给 {@code remittanceBankName}，
     * 工具收 {@code remittancePlatformName}。两边都合法、都过各自的校验，用户看到转账失败。
     */
    @Test
    @DisplayName("槽位名漂移即拒绝：声明了主 Agent 抽不出来的名字")
    void slotNameDriftIsRejected() {
        List<AssetLint.Finding> errors = AssetLint.errors(bundleWith(
                card("cap.bad.drifted-slot", "向指定收款人转账。本能力不涉及跨境汇款",
                        List.of("remittanceBankName"))));

        assertThat(errors).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("UNKNOWN_SLOT");
            // 理由里要带上「已知可抽取有哪些」，否则评审人只知道错了不知道该写什么
            assertThat(f.detail()).contains("remittanceBankName").contains("payee");
        });
    }

    @Test
    @DisplayName("声明了没有澄清话术的槽位即拒绝：缺它时用户只会拿到一句通用兜底")
    void slotWithoutClarifyQuestionIsRejected() {
        // cardNumber 抽得出来，但 clarify.yaml 里没有它的问法——
        // 这个组合是「合法但办不成」的典型：每一环都对，能力实际上永远走不完
        List<AssetLint.Finding> errors = AssetLint.errors(bundleWith(
                card("cap.bad.unclarifiable", "按卡号查询交易。本能力不涉及他人账户",
                        List.of("cardNumber"))));

        assertThat(errors).anySatisfy(f -> assertThat(f.rule()).isEqualTo("NO_CLARIFY_QUESTION"));
    }

    @Test
    @DisplayName("两张卡挂同一条 utterance 即拒绝，理由列出冲突双方")
    void utteranceCollisionIsRejected() {
        CapabilityCard a = cardWithUtterances("cap.bad.a", List.of("查一下余额"));
        CapabilityCard b = cardWithUtterances("cap.bad.b", List.of("查一下余额"));

        List<AssetLint.Finding> errors = AssetLint.errors(bundleWith(a, b));

        assertThat(errors).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("UTTERANCE_COLLISION");
            assertThat(f.where()).contains("cap.bad.a").contains("cap.bad.b");
        });
    }

    @Test
    @DisplayName("同一个 capabilityId 出现两次即拒绝：后者会静默覆盖前者")
    void duplicateIdIsRejected() {
        List<AssetLint.Finding> errors = AssetLint.errors(bundleWith(
                card("cap.dup", "查询余额。本能力不涉及他人账户", List.of()),
                card("cap.dup", "另一张同 id 的卡。本能力不涉及他人账户", List.of())));

        assertThat(errors).anySatisfy(f -> assertThat(f.rule()).isEqualTo("DUPLICATE_ID"));
    }

    @Test
    @DisplayName("描述过短即拒绝，且不再追加负向边界那一条——一条卡不该收到两句在说同一件事的意见")
    void tooShortDescriptionIsRejected() {
        List<AssetLint.Finding> errors = AssetLint.errors(bundleWith(
                card("cap.bad.terse", "查余额", List.of())));

        assertThat(errors).anySatisfy(f -> assertThat(f.rule()).isEqualTo("DESCRIPTION_TOO_SHORT"));
        assertThat(errors).noneSatisfy(f ->
                assertThat(f.rule()).isEqualTo("MISSING_NEGATIVE_BOUNDARY"));
    }

    @Test
    @DisplayName("utterance 偏少与缺 keywords 只报 WARN：算不算问题需要业务上下文，机器判不了")
    void thinRecallTextIsOnlyWarned() {
        List<AssetLint.Finding> all = AssetLint.inspect(bundleWith(
                cardWithUtterances("cap.thin", List.of("就一条"))));

        assertThat(all).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("TOO_FEW_UTTERANCES");
            assertThat(f.severity()).isEqualTo(AssetLint.Severity.WARN);
        });
        assertThat(AssetLint.errors(bundleWith(cardWithUtterances("cap.thin", List.of("就一条")))))
                .noneSatisfy(f -> assertThat(f.rule()).isEqualTo("TOO_FEW_UTTERANCES"));
    }

    @Test
    @DisplayName("标准问答：写歪的模版、空答案、指错的动作入口一律拒绝合并")
    void badStandardQaIsRejected() {
        StandardQaBank.Entry broad = qa("qa.broad", "怎么办", List.of("*"), "随便答一句。");
        assertThat(AssetLint.errors(bundleWithQa(broad)))
                .as("过宽的模版会把大片流量吸进标准答案，而它们本该走能力召回")
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_BAD_PATTERN"));

        StandardQaBank.Entry empty = qa("qa.empty", "转账手续费怎么算",
                List.of("转账手续费怎么算"), "");
        assertThat(AssetLint.errors(bundleWithQa(empty)))
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_EMPTY_ANSWER"));

        StandardQaBank.Entry dangling = qa("qa.dangling", "怎么转账",
                List.of("怎么转账"), "在首页点转账。");
        dangling.setActionCapabilityId("cap.does.not.exist");
        assertThat(AssetLint.errors(bundleWithQa(dangling)))
                .as("动作入口指向不存在的能力，用户点了会掉进空处")
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_UNKNOWN_ACTION"));
    }

    @Test
    @DisplayName("阻断知识：必须有安全引导，且不得夹带答案或直接执行动作")
    void blockedKnowledgeCannotCarryUnreviewedAnswerOrAction() {
        StandardQaBank.Entry missingGuidance = qa("qa.blocked.empty", "原号换卡",
                List.of("原号换卡"), "");
        missingGuidance.setStatus(StandardQaBank.Entry.Status.BLOCKED_SOURCE_REVIEW);
        assertThat(AssetLint.errors(bundleWithQa(missingGuidance)))
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_BLOCKED_WITHOUT_GUIDANCE"));

        StandardQaBank.Entry leakedAnswer = qa("qa.blocked.answer", "原号换卡",
                List.of("原号换卡"), "未经审批的答案");
        leakedAnswer.setStatus(StandardQaBank.Entry.Status.BLOCKED_SOURCE_REVIEW);
        leakedAnswer.setGuidance("请选择下一步。");
        assertThat(AssetLint.errors(bundleWithQa(leakedAnswer)))
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_BLOCKED_WITH_ANSWER"));

        StandardQaBank.Entry directAction = qa("qa.blocked.action", "原号换卡",
                List.of("原号换卡"), "");
        directAction.setStatus(StandardQaBank.Entry.Status.BLOCKED_SOURCE_REVIEW);
        directAction.setGuidance("请选择下一步。");
        directAction.setActionCapabilityId("cap.card.replace");
        assertThat(AssetLint.errors(bundleWithQa(directAction)))
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_BLOCKED_WITH_ACTION"));
    }

    @Test
    @DisplayName("标准问答：被前一条吃掉的条目要拦下来，它改了也不会生效")
    void shadowedStandardQaIsRejected() {
        StandardQaBank.Entry broad = qa("qa.fee.any", "转账手续费怎么算",
                List.of("转账*手续费怎么{算|收}"), "跨行每笔 2 元。");
        StandardQaBank.Entry narrow = qa("qa.fee.cross", "转账跨行手续费怎么算",
                List.of("转账跨行手续费怎么算"), "跨行每笔 2 元，单笔封顶 50 元。");

        assertThat(AssetLint.errors(bundleWithQa(broad, narrow)))
                .anySatisfy(f -> {
                    assertThat(f.rule()).isEqualTo("QA_SHADOWED");
                    assertThat(f.where()).isEqualTo("qa.fee.cross");
                });
    }

    @Test
    @DisplayName("标准问答：答案里的变量必须声明，且得是真抽得出来的槽位")
    void undeclaredAnswerVariableIsRejected() {
        StandardQaBank.Entry undeclared = qa("qa.balance", "我的余额是多少",
                List.of("我的余额是多少"), "您的余额是 ${amount} 元。");
        assertThat(AssetLint.errors(bundleWithQa(undeclared)))
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_UNDECLARED_SLOT"));

        StandardQaBank.Entry unknown = qa("qa.weird", "这是什么",
                List.of("这是什么"), "答案是 ${notASlot}。");
        unknown.setRequiredSlots(List.of("notASlot"));
        assertThat(AssetLint.errors(bundleWithQa(unknown)))
                .as("声明了也没用，抽不出来的槽位永远取不到值，这条会永不命中")
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo("QA_UNKNOWN_SLOT"));
    }

    private static StandardQaBank.Entry qa(String id, String question, List<String> patterns,
                                           String answer) {
        StandardQaBank.Entry entry = new StandardQaBank.Entry();
        entry.setId(id);
        entry.setQuestion(question);
        entry.setPatterns(patterns);
        entry.setAnswer(answer);
        return entry;
    }

    private static AssetBundle bundleWithQa(StandardQaBank.Entry... entries) {
        StandardQaBank bank = new StandardQaBank();
        bank.setItems(List.of(entries));
        return real.withStandardQa(bank);
    }

    /**
     * 只换能力卡清单，其余资产用真实那份——澄清话术与模板映射都要真的才有判定意义。
     *
     * <p>负向规则例外，要一并清掉。它们的 {@code suppress} 指向真实资产里的能力，
     * 而这里的卡清单是刻意截短的：留着就等于让每个卡级用例都顺带触发一堆
     * 「suppress 指向不存在的能力」——那是夹具截短造成的，不是被测卡的问题。
     * 真实资产的 suppress 一致性由 {@code realAssetsPassLint} 覆盖，它用的是完整的 {@code real}。
     */
    private static AssetBundle bundleWith(CapabilityCard... extra) {
        List<CapabilityCard> cards = new ArrayList<>(List.of(extra));
        AssetBundle narrowed = real.withCapabilities(cards);
        return new AssetBundle(narrowed.assetVersion(), narrowed.declaredVersion(),
                narrowed.capabilities(), narrowed.strongRules(), List.of(), narrowed.fusion(),
                narrowed.synonyms(), narrowed.clarify(), narrowed.templates(),
                narrowed.capabilityTemplates(), narrowed.arbitrationSkill(),
                narrowed.complianceTopics(), new StandardQaBank(), narrowed.techDomains(),
                narrowed.menus());
    }

    private static CapabilityCard card(String id, String description, List<String> requiredSlots) {
        return new CapabilityCard(id, "某能力", Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.account", List.of("account"), description, List.of("意图"),
                Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 3000,
                Enums.Idempotency.SUPPORTED, "owner", "1.0.0", Enums.CapabilityStatus.ACTIVE,
                List.of("说法一", "说法二", "说法三"), List.of("关键词"), requiredSlots, null);
    }

    private static CapabilityCard cardWithUtterances(String id, List<String> utterances) {
        return new CapabilityCard(id, "某能力", Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.account", List.of("account"), "查询某项信息。本能力不涉及他人账户",
                List.of("意图"), Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 3000,
                Enums.Idempotency.SUPPORTED, "owner", "1.0.0", Enums.CapabilityStatus.ACTIVE,
                utterances, List.of(), List.of(), null);
    }
}
