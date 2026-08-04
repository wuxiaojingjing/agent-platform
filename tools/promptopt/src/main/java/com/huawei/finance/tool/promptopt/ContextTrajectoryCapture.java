package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freezes exact production-rendered context-rewrite inputs for prompt optimization. */
public final class ContextTrajectoryCapture {

    private static final String ACCOUNT_REF = "fact:account-alias";
    private static final String BALANCE_REF = "fact:balance-snapshot";
    private static final String ACCOUNTS_REF = "fact:accounts";

    private ContextTrajectoryCapture() {
    }

    public static void main(String[] args) throws Exception {
        Path assetsPath = Path.of("../../agents/mobile-banking-assistant/assets");
        var bundle = new AssetLoader(new ContractValidator()).load(assetsPath);
        var skill = bundle.contextRewriteSkill();

        Fixture balance = balanceFixture();
        Fixture transfer = taskFixture("transfer", "给张三转1000", "cap.transfer", "REVIEW");
        Fixture replaceCard = taskFixture("replace", "换卡", "cap.card.replace", "CLARIFY");
        Fixture billReview = taskFixture("bill-review", "查询信用卡账单", "cap.creditcard.bill.query", "REVIEW");
        Fixture suspendedReplace = taskFixture(
                "replace-suspended", "换卡", "cap.card.replace", "SUSPENDED");
        Fixture empty = new Fixture("", Map.of(), List.of(), List.of());
        Fixture longBankingHistory = longBankingHistoryFixture();

        List<Seed> seeds = List.of(
                ratio("half-verb-first", "转一半给张三", balance, "张三"),
                ratio("half-balance-object", "给张三转余额的一半", balance, "张三"),
                ratio("half-explicit-prior", "把刚才余额的一半转给张三", balance, "张三"),
                ratio("half-payee-first", "给张三转一半", balance, "张三"),
                ratio("half-another-payee", "再把一半转给李四", balance, "李四"),
                seed("ordinal-account", "第二张呢", balance,
                        truth(true, "SUPPLEMENT", "ORDINAL_REFERENCE",
                                Map.of("accountOrdinal", "2"), List.of(ACCOUNTS_REF), List.of("第二"), List.of())),
                seed("ordinal-out-of-range", "第三张呢", balance,
                        truth(false, null, null, Map.of(), List.of(), List.of("第三张"),
                                List.of("accountOrdinal"))),
                seed("absolute-amount", "给张三转1000", balance,
                        noContext("NEW_TASK", List.of("给张三转1000"), List.of("amountBasis"))),
                seed("explicit-account", "查尾号3344的余额", balance,
                        noContext("NEW_TASK", List.of("尾号3344"), List.of("accountOrdinal"))),
                seed("cancel-active-task", "取消", transfer,
                        noContext("CANCEL", List.of("取消"), List.of())),
                seed("cancel-without-task", "取消", empty,
                        noContext("CANCEL", List.of("取消"), List.of())),
                seed("independent-question", "怎么开通短信提醒", balance,
                        noContext("NEW_TASK", List.of("短信提醒"), List.of())),
                seed("topic-switch", "查一下信用卡账单", balance,
                        noContext("NEW_TASK", List.of("信用卡账单"), List.of())),
                seed("payee-correction", "不是张三，是李四", transfer,
                        truth(true, "CORRECTION", "CORRECTION", Map.of("payee", "李四"),
                                List.of(historyRef("transfer")), List.of("李四"), List.of())),
                seed("amount-correction", "金额改成2000", transfer,
                        truth(true, "CORRECTION", "CORRECTION", Map.of("amount", "2000"),
                                List.of(historyRef("transfer")), List.of("2000"), List.of())),
                seed("payee-coreference", "给他再转500", transfer,
                        truth(true, "SUPPLEMENT", "COREFERENCE", Map.of("payee", "张三"),
                                List.of(historyRef("transfer")), List.of("张三", "500"), List.of())),
                seed("replace-card-slot", "信用卡", replaceCard,
                        truth(true, "SUPPLEMENT", "ELLIPSIS", Map.of("cardType", "信用卡"),
                                List.of(historyRef("replace")), List.of("换卡", "信用卡"), List.of())),
                seed("review-accept", "就按这个办", billReview,
                        truth(true, "REVIEW_ACCEPT", "TASK_REFERENCE", Map.of(),
                                List.of(historyRef("bill-review")), List.of("信用卡账单"), List.of())),
                seed("review-accept-explicit", "接受这个方案", billReview,
                        truth(true, "REVIEW_ACCEPT", "TASK_REFERENCE", Map.of(),
                                List.of(historyRef("bill-review")), List.of("信用卡账单"), List.of())),
                seed("explicit-confirmation", "确认执行", transfer,
                        truth(true, "CONFIRMATION", "TASK_REFERENCE", Map.of(),
                                List.of(historyRef("transfer")), List.of("转"), List.of())),
                seed("natural-confirmation", "可以执行", transfer,
                        truth(true, "CONFIRMATION", "TASK_REFERENCE", Map.of(),
                                List.of(historyRef("transfer")), List.of("转"), List.of())),
                seed("resume-suspended", "继续换卡", suspendedReplace,
                        truth(true, "RESUME_SUSPENDED", "TASK_REFERENCE", Map.of(),
                                List.of(historyRef("replace-suspended")), List.of("换卡"), List.of())),
                seed("half-without-source", "给张三转一半", empty,
                        noContext("NEW_TASK", List.of("给张三转一半"),
                                List.of("amount", "amountBasis"))),
                seed("long-bank-history-independent-balance", "查看我在招行有多少钱",
                        longBankingHistory,
                        noContext("NEW_TASK", List.of("招行", "多少钱"),
                                List.of("accountOrdinal", "amountBasis"))));

        List<ContextTrajectory> trajectories = seeds.stream().map(seed -> {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("originalQuery", seed.query());
            vars.put("stateVersion", "1");
            vars.put("goal", seed.fixture().goal());
            vars.put("confirmedFacts", json(seed.fixture().confirmedFacts()));
            vars.put("conversationHistory", json(seed.fixture().history()));
            vars.put("knowledgeExamples", "[]");
            vars.put("availableContext", json(seed.fixture().available()));
            return new ContextTrajectory(seed.id(), seed.query(), skill.renderUser(vars),
                    seed.fixture().history(), bundle.assetVersion(), seed.truth());
        }).toList();

        Path output = Path.of("../../agents/mobile-banking-assistant/eval/context-rewrite-trajectories.json");
        Files.createDirectories(output.getParent());
        ObjectMapper mapper = ContractJson.mapper();
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(trajectories));
        System.out.printf("已冻结 %d 条 context-rewrite 轨迹：%s%n",
                trajectories.size(), output.toAbsolutePath().normalize());
    }

    private static Seed ratio(String id, String query, Fixture fixture, String payee) {
        return seed(id, query, fixture,
                truth(true, null, "REQUERY_THEN_HALF",
                        Map.of("amountBasis", "REQUERY_THEN_HALF"),
                        List.of(ACCOUNT_REF), List.of("重查", payee), List.of("amount")));
    }

    private static Seed seed(String id, String query, Fixture fixture, ContextTrajectory.Truth truth) {
        return new Seed(id, query, fixture, truth);
    }

    private static ContextTrajectory.Truth noContext(
            String eventType, List<String> standaloneContains, List<String> forbiddenSlots) {
        return truth(false, eventType, null, Map.of(), List.of(), standaloneContains, forbiddenSlots);
    }

    private static ContextTrajectory.Truth truth(
            boolean consumed, String eventType, String resolutionType, Map<String, String> slots,
            List<String> usedRefs, List<String> standaloneContains, List<String> forbiddenSlots) {
        return new ContextTrajectory.Truth(consumed, eventType, resolutionType, slots,
                usedRefs, standaloneContains, forbiddenSlots);
    }

    private static Fixture balanceFixture() {
        String ref = historyRef("balance");
        Map<String, Object> history = turn(ref, "查一下余额", "cap.account.balance.query",
                "NONE", "SUCCEEDED");
        List<Map<String, Object>> available = new ArrayList<>();
        available.add(fact(ACCOUNT_REF, Map.of("accountAlias", "尾号 8821 借记卡")));
        available.add(fact(BALANCE_REF, Map.of("availableBalance", "12,845.60")));
        available.add(fact(ACCOUNTS_REF, Map.of("cards", List.of(
                Map.of("index", 1, "alias", "尾号 8821 借记卡"),
                Map.of("index", 2, "alias", "尾号 3344 借记卡")))));
        available.add(history);
        return new Fixture("查一下余额", Map.of(), messages(ref, "查一下余额",
                "cap.account.balance.query", "NONE", "SUCCEEDED",
                Map.of("availableBalance", "12,845.60")), List.copyOf(available));
    }

    private static Fixture taskFixture(String id, String text, String capability, String pending) {
        String ref = historyRef(id);
        Map<String, Object> history = turn(ref, text, capability, pending, null);
        return new Fixture(text, Map.of(), messages(ref, text, capability, pending, null, Map.of()),
                List.of(history));
    }

    private static Map<String, Object> turn(
            String ref, String text, String capability, String pending, String outcome) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", text);
        value.put("decision", "EXECUTE_CAPABILITY");
        value.put("capabilityId", capability);
        value.put("pending", pending);
        if (outcome != null) value.put("outcome", outcome);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", ref);
        result.put("kind", "USER_TURN");
        result.put("value", Map.copyOf(value));
        result.put("sourceTurnRef", sourceTurnRef(ref));
        result.put("sensitivity", "SENSITIVE");
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> messages(
            String ref, String text, String capability, String pending,
            String outcome, Map<String, Object> facts) {
        String sourceTurnRef = sourceTurnRef(ref);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", ref, sourceTurnRef, text));
        messages.add(message("assistant", sourceTurnRef + ":assistant", sourceTurnRef,
                Map.of("decision", "EXECUTE_CAPABILITY", "capabilityId", capability)));
        Map<String, Object> result = new LinkedHashMap<>();
        if (outcome != null) result.put("outcome", outcome);
        result.put("pending", pending);
        if (!facts.isEmpty()) result.put("facts", facts);
        messages.add(message("tool", sourceTurnRef + ":tool", sourceTurnRef, Map.copyOf(result)));
        return List.copyOf(messages);
    }

    private static Fixture longBankingHistoryFixture() {
        List<Map<String, Object>> history = new ArrayList<>();
        addExchange(history, "3", "查看我的建行卡余额",
                "NoAvailableTool(appName=建行)",
                Map.of("result", "succeed", "assistantReply", "请通过中国建设银行官方渠道查询余额"));
        addExchange(history, "4", "给我爸转账1元",
                "TransferRemittance(currencyAmount=1元,payee=我爸)",
                Map.of("result", "succeed", "status", "select",
                        "assistantReply", "当前有中国建设银行和中信百信银行可选"));
        addExchange(history, "5", "看看我建行卡上个月花了多少钱",
                "CheckBankTransactionDetails(time=上个月,bankName=建行)",
                Map.of("result", "succeed", "assistantReply", ""));
        addExchange(history, "6", "看看我建行买的理财收益多少了",
                "ViewFinancialProduct(appName=建行)",
                Map.of("result", "succeed", "assistantReply", "请在建设银行手机银行的持仓收益中查看"));
        addExchange(history, "7", "我想用建行交话费",
                "RechargeCallFee(rechargePlatformName=建行)",
                Map.of("result", "succeed", "assistantReply", "请在建设银行生活服务中办理话费充值"));
        addExchange(history, "8", "查看我在招行的收支情况",
                "CheckBankTransactionDetails(bankName=招行)",
                Map.of("result", "succeed", "assistantReply", ""));
        addExchange(history, "9", "查看招行理财产品有哪些",
                "ViewFinancialProduct(appName=招行)",
                Map.of("result", "succeed", "assistantReply", "招商银行提供多类不同风险等级的理财产品"));
        return new Fixture("查看招行理财产品有哪些", Map.of(), List.copyOf(history), List.of());
    }

    private static void addExchange(List<Map<String, Object>> history, String turnId,
                                    String userText, String toolCall,
                                    Map<String, Object> toolResult) {
        String sourceTurnRef = "turn:context-opt-long-bank#" + turnId;
        history.add(message("user", sourceTurnRef + ":utterance", sourceTurnRef, userText));
        history.add(message("assistant", sourceTurnRef + ":assistant", sourceTurnRef, toolCall));
        history.add(message("tool", sourceTurnRef + ":tool", sourceTurnRef, toolResult));
    }

    private static Map<String, Object> message(String role, String ref,
                                                String sourceTurnRef, Object content) {
        return Map.of("role", role, "ref", ref,
                "sourceTurnRef", sourceTurnRef, "content", content);
    }

    private static String sourceTurnRef(String ref) {
        return ref.substring(0, ref.indexOf(":utterance"));
    }

    private static Map<String, Object> fact(String ref, Map<String, Object> value) {
        return Map.of("ref", ref, "kind", "TOOL_FACT", "value", value,
                "sensitivity", "SENSITIVE");
    }

    private static String historyRef(String id) {
        return "turn:context-opt-" + id + "#0:utterance";
    }

    private static String json(Object value) {
        try {
            return ContractJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(String goal, Map<String, Object> confirmedFacts,
                           List<Map<String, Object>> history,
                           List<Map<String, Object>> available) {
    }

    private record Seed(String id, String query, Fixture fixture, ContextTrajectory.Truth truth) {
    }
}
