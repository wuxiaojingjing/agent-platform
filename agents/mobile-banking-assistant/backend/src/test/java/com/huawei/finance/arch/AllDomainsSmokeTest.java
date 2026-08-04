package com.huawei.finance.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.a2a.A2AGateway;
import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardProjector;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.InMemoryDelegationStore;
import com.huawei.finance.a2a.node.DomainAgentExecutor;
import com.huawei.finance.a2a.node.DomainAgentNode;
import com.huawei.finance.a2a.node.KeywordGoalResolver;
import com.huawei.finance.a2a.node.ScaffoldNodeFactory;
import com.huawei.finance.domain.account.AccountDomainAgent;
import com.huawei.finance.domain.account.AccountPort;
import com.huawei.finance.domain.creditcard.CreditcardDomainAgent;
import com.huawei.finance.domain.creditcard.CreditcardPort;
import com.huawei.finance.domain.finance.FinanceDomainAgent;
import com.huawei.finance.domain.finance.NavigationCatalogPort;
import com.huawei.finance.domain.fund.FundDomainAgent;
import com.huawei.finance.domain.fund.FundProductPort;
import com.huawei.finance.domain.insurance.InsuranceDomainAgent;
import com.huawei.finance.domain.insurance.InsuranceProductPort;
import com.huawei.finance.domain.transfer.TransferDomainAgent;
import com.huawei.finance.domain.transfer.TransferPort;
import com.huawei.finance.domain.wealth.WealthDomainAgent;
import com.huawei.finance.domain.wealth.WealthPort;
import com.huawei.finance.domain.deposit.DepositCatalogPort;
import com.huawei.finance.domain.deposit.DepositDomainAgent;
import com.huawei.finance.domain.loan.LoanCatalogPort;
import com.huawei.finance.domain.loan.LoanDomainAgent;
import com.huawei.finance.domain.payroll.PayrollDomainAgent;
import com.huawei.finance.domain.payroll.PayrollStatusPort;
import com.huawei.finance.domain.wealthproduct.WealthProductDomainAgent;
import com.huawei.finance.domain.wealthproduct.WealthProductPort;
import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.port.TechDomainAgent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 附录 F 全量域可达性冒烟：已交付真节点办成，其余显式 DOMAIN_NOT_OPEN。
 */
@DisplayName("全量科技域冒烟")
class AllDomainsSmokeTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final List<String> CARD_LOCATIONS = new A2AProperties().resolveCardLocations();
    private static final String ENTRY = "mobile-banking-assistant";

    /** 阶段 3a/3b 首批已交付：账户 + 本批 6 域。 */
    private static final Set<String> DELIVERED_AGENTS = Set.of(
            "agent.account",
            "agent.transfer",
            "agent.creditcard",
            "agent.wealth_aggregate",
            "agent.fund_service",
            "agent.insurance_service",
            "agent.finance_assistant",
            "agent.deposit_service",
            "agent.loan_service",
            "agent.payroll_service",
            "agent.wealth_product");

    private static final Map<String, String> DELIVERED_CAPS = Map.ofEntries(
            Map.entry("agent.account", "cap.account.balance.query"),
            Map.entry("agent.transfer", "cap.transfer"),
            Map.entry("agent.creditcard", "cap.creditcard.bill.query"),
            Map.entry("agent.wealth_aggregate", "cap.wealth.holding.query"),
            Map.entry("agent.fund_service", "cap.fund.product.query"),
            Map.entry("agent.insurance_service", "cap.insurance.product.query"),
            Map.entry("agent.finance_assistant", "cap.nav.wealth_aggregate_查询我的资产"),
            Map.entry("agent.deposit_service", "cap.deposit.product.query"),
            Map.entry("agent.loan_service", "cap.loan.product.query"),
            Map.entry("agent.payroll_service", "cap.payroll.status.query"),
            Map.entry("agent.wealth_product", "cap.wealth-product.product.query"));

    @Test
    @DisplayName("26 个域都投得到：要么办成，要么显式未开放，没有一个静默消失")
    void everyDomainIsReachable() {
        List<AgentCard> cards = cards();
        A2AGateway gateway = gateway(cards);

        assertThat(cards).hasSize(26);

        AgentCardRegistry registry = registry(cards);

        for (AgentCard card : cards) {
            assertThat(registry.node(card.agentId()))
                    .as("域 %s（%s）在路由表里没有节点——它静默消失了",
                            card.techDomainCode(), card.agentId())
                    .isPresent();

            DelegationReceipt receipt = gateway.dispatch(task(card.agentId(),
                    "cap." + card.techDomainCode() + ".smoke.query", "smoke-" + card.agentId()));

            assertThat(receipt.outcome())
                    .as("域 %s 既没办成也没显式未开放", card.techDomainCode())
                    .isIn(DelegationOutcome.SUCCEEDED, DelegationOutcome.DOMAIN_NOT_OPEN,
                            DelegationOutcome.NOT_MINE, DelegationOutcome.NEED_USER);
        }
    }

    @Test
    @DisplayName("投给一个不在附录 F 的 agentId：AGENT_UNKNOWN，不和「域未交付」混为一谈")
    void unknownAgentIsNotConfusedWithUndelivered() {
        DelegationReceipt receipt = gateway(cards())
                .dispatch(task("agent.does_not_exist", "cap.x.y.z", "unknown-1"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("AGENT_UNKNOWN");
    }

    @Test
    @DisplayName("未开放的域回 DOMAIN_NOT_OPEN，且事实为空——不回假数据")
    void undeliveredDomainsFailExplicitly() {
        List<AgentCard> cards = cards();
        A2AGateway gateway = gateway(cards);

        long checked = 0;
        for (AgentCard card : cards) {
            if (DELIVERED_AGENTS.contains(card.agentId())) {
                continue;
            }
            DelegationReceipt receipt = gateway.dispatch(task(card.agentId(),
                    "cap." + card.techDomainCode() + ".smoke.query", "notopen-" + card.agentId()));

            assertThat(receipt.outcome())
                    .as("域 %s 未交付，应显式回 DOMAIN_NOT_OPEN", card.techDomainCode())
                    .isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
            assertThat(receipt.facts())
                    .as("未开放的域给了事实，那是假数据——面客链路上和真数据长得一样")
                    .isEmpty();
            checked++;
        }
        assertThat(checked).as("一个未交付域都没验到，这条冒烟在空转")
                .isEqualTo(26 - DELIVERED_AGENTS.size());
    }

    @Test
    @DisplayName("已交付的域照常办成，事实结构化")
    void deliveredDomainsSucceed() {
        A2AGateway gateway = gateway(cards());
        for (String agentId : DELIVERED_AGENTS) {
            String cap = DELIVERED_CAPS.get(agentId);
            DelegationReceipt receipt = gateway.dispatch(
                    task(agentId, cap, "delivered-" + agentId));

            assertThat(receipt.outcome())
                    .as("已交付域 %s 应办成", agentId)
                    .isEqualTo(DelegationOutcome.SUCCEEDED);
            assertThat(receipt.facts())
                    .as("已交付域 %s 事实为空", agentId)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("未开放 Agent 拒绝 GOAL：统一返回 DOMAIN_NOT_OPEN")
    void scaffoldRejectsGoal() {
        AgentCard scaffold = cards().stream()
                .filter(c -> !DELIVERED_AGENTS.contains(c.agentId()))
                .findFirst().orElseThrow();

        DelegationReceipt receipt = gateway(cards()).dispatch(new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "t", ENTRY, scaffold.agentId(),
                "root", "parent", "src", "goal-scaffold", "trace", DelegationMode.GOAL,
                "帮我办点什么", null, Map.of(), List.of(), NOW.plusSeconds(30), List.of(ENTRY)));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
        assertThat(receipt.reasonCode()).isEqualTo("DOMAIN_NOT_OPEN");
    }

    @Test
    @DisplayName("多域拓扑上重跑环路：A→B→A 被拒")
    void loopIsRejectedOnMultiDomainTopology() {
        List<AgentCard> cards = cards();
        String other = cards.stream().map(AgentCard::agentId)
                .filter(a -> !DELIVERED_AGENTS.contains(a)).findFirst().orElseThrow();

        DelegationReceipt receipt = gateway(cards).dispatch(new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "t", "agent.account", other,
                "root", "parent", "src", "loop-multi", "trace", DelegationMode.TASK,
                null, "cap.smoke.x.query", Map.of(), List.of(), NOW.plusSeconds(30),
                List.of(ENTRY, other)));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("DELEGATION_LOOP");
    }

    @Test
    @DisplayName("多域拓扑上重跑深度：第四跳被拒")
    void depthIsBoundedOnMultiDomainTopology() {
        List<AgentCard> cards = cards();
        List<String> threeHops = cards.stream().map(AgentCard::agentId).limit(3).toList();

        DelegationReceipt receipt = gateway(cards).dispatch(new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "t", threeHops.get(2), "agent.account",
                "root", "parent", "src", "depth-multi", "trace", DelegationMode.TASK,
                null, "cap.account.balance.query", Map.of(), List.of(),
                NOW.plusSeconds(30), threeHops));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("DELEGATION_DEPTH_EXCEEDED");
    }

    private static List<AgentCard> cards() {
        List<AgentCard> cards = new AgentCardProjector(CARD_LOCATIONS).project();
        assertThat(cards).as("一张卡都没投出来，这套冒烟在空转").isNotEmpty();
        return cards;
    }

    /** 已交付域装真节点，其余由 ScaffoldNodeFactory 补齐。 */
    private static AgentCardRegistry registry(List<AgentCard> cards) {
        List<AgentNode> delivered = deliveredNodes();
        List<AgentNode> all = new ArrayList<>(delivered);
        all.addAll(ScaffoldNodeFactory.fillGaps(cards, delivered));
        return new AgentCardRegistry(cards, all);
    }

    private static List<AgentNode> deliveredNodes() {
        List<AgentNode> nodes = new ArrayList<>();
        for (TechDomainAgent leaf : deliveredLeaves()) {
            nodes.add(new DomainAgentNode(leaf.agentId(), List.of(leaf.techDomainCode()),
                    new DomainAgentExecutor(leaf, new KeywordGoalResolver(Map.of())), true));
        }
        return nodes;
    }

    private static List<TechDomainAgent> deliveredLeaves() {
        return List.of(
                new AccountDomainAgent(new AccountPort() {
                    public AccountView accountView(String principal) {
                        return new AccountView(List.of(new CardView(1, "测试账户", "100.00")));
                    }
                    public List<TransactionView> transactions(String principal) { return List.of(); }
                }),
                new TransferDomainAgent(command -> new TransferPort.TransferReceipt(
                        command.payee(), command.amount(), command.fromAccount(), "TR-TEST", "now")),
                new CreditcardDomainAgent(new CreditcardPort() {
                    public BillView bill(String principal, String cardRef) {
                        return new BillView("100.00", "2026-08-10");
                    }
                    public OperationReceipt repay(RepayCommand command) {
                        return new OperationReceipt("RP-TEST", command.amount(), "");
                    }
                    public OperationReceipt replace(ReplaceCommand command) {
                        return new OperationReceipt("RC-TEST", "", command.cardType());
                    }
                }),
                new WealthDomainAgent(principal -> new WealthPort.HoldingView("100.00", "1.00")),
                new FundDomainAgent(principal -> new FundProductPort.ProductView(
                        "F", "基金", "基金", "R3", "3%", "开放式")),
                new InsuranceDomainAgent(principal -> new InsuranceProductPort.ProductView(
                        "I", "保险", "保险", "R2", "-", "终身")),
                new DepositDomainAgent(() -> new DepositCatalogPort.ProductView(
                        "D", "定期", "存款", "R0", "2%", "1年")),
                new LoanDomainAgent(() -> new LoanCatalogPort.ProductView(
                        "L", "消费贷", "贷款", "R1", "以审批为准", "5年")),
                new PayrollDomainAgent(principal -> new PayrollStatusPort.StatusView(
                        "已到账", "2026-07-23", "测试单位")),
                new WealthProductDomainAgent(ignored -> new WealthProductPort.ProductView(
                        "W", "稳健理财", "理财", "R2", "比较基准", "180天")),
                new FinanceDomainAgent(new NavigationCatalogPort() {
                    public Map<String, Object> find(String capability) {
                        return Map.of("menuId", "m1", "menuName", "测试菜单", "bksPath", "/test");
                    }
                    public Set<String> capabilities() { return Set.of("cap.nav.wealth_aggregate_查询我的资产"); }
                }));
    }

    private static A2AGateway gateway(List<AgentCard> cards) {
        return new A2AGateway(registry(cards), new InMemoryDelegationStore(),
                new A2AProperties(), new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DelegationEnvelope task(String target, String capabilityId, String id) {
        // 幂等键：叶子要求 executable；冒烟用稳定假键
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("principalRef", "principal:test");
        params.put("payee", "张三");
        params.put("amount", "100");
        params.put("cardType", "CREDIT");
        params.put("cardRef", "card:test");
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t-1", ENTRY,
                target, "root-1", "parent-1", "src-1", id, "trace-1",
                DelegationMode.TASK, null, capabilityId, params, List.of(),
                NOW.plusSeconds(30), List.of(ENTRY));
    }
}
