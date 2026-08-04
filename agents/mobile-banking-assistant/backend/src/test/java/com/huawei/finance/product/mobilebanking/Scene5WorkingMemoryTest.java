package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.product.mobilebanking.console.RecentDecisions;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ResponseAction;
import com.huawei.finance.runtime.ActionEvent;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import com.huawei.finance.domain.account.AccountPort;
import com.huawei.finance.domain.creditcard.CreditcardPort;
import com.huawei.finance.domain.transfer.TransferPort;
import com.huawei.finance.domain.fund.FundProductPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 场景 5：查余额后「用第二张卡转一半给张三」→ 工作记忆解析出账账户与半额，停在确认闸门。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Scene5WorkingMemoryTest.ContextBackends.class)
class Scene5WorkingMemoryTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final AtomicReference<String> SECOND_CARD_BALANCE = new AtomicReference<>("8,000.00");
    private static final AtomicReference<TransferPort.TransferCommand> LAST_TRANSFER = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BILL_CARD_REF = new AtomicReference<>();
    private static final AtomicInteger BILL_CALLS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AgentIdentity agentIdentity;

    @Autowired
    private RecentDecisions recentDecisions;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过场景 5 端到端");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过场景 5 端到端");
    }

    private static boolean redisAnswersPing() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[8];
            int read = socket.getInputStream().read(buffer);
            return read > 0 && new String(buffer, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean postgresAccepts() {
        try (Connection ignored = DriverManager.getConnection(PG_URL, "agent_platform", "agent_platform")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @BeforeEach
    void clearDecisionCache() {
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
        SECOND_CARD_BALANCE.set("8,000.00");
        LAST_TRANSFER.set(null);
        LAST_BILL_CARD_REF.set(null);
        BILL_CALLS.set(0);
    }

    @Test
    @DisplayName("查余额 → 第二张卡转一半给张三 → CONFIRM，仅展示延迟求值依据")
    void secondCardHalfTransferNeedsConfirm() {
        String session = "s-" + UUID.randomUUID();

        ChatResponseDto balance = chat(session, "查一下余额");
        assertThat(balance.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(balance.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(balance.text()).contains("8821");

        ChatResponseDto transfer = chat(session, "用第二张卡转一半给张三");
        assertThat(transfer.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(transfer.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(transfer.plan().templateKey()).isEqualTo("tpl.transfer.confirm");
        assertThat(transfer.text())
                .contains("张三")
                .contains("执行时权威可用余额的一半")
                .contains("3344");
        assertThat(transfer.plan().slots())
                .containsEntry("payee", "张三")
                .containsEntry("amountBasis", "REQUERY_THEN_HALF")
                .containsEntry("fromAccount", "尾号 3344 借记卡")
                .doesNotContainKey("amount");

        assertThat(taskRepository.findActiveBySession(agentIdentity.id(), session))
                .isPresent()
                .get()
                .extracting(t -> t.state())
                .isEqualTo(TaskState.CONFIRM_PENDING);
        assertRawHistoryVisibleToDownstreamModules(session, "用第二张卡转一半给张三", "查一下余额");

        // The review confirms the relative basis, not a number derived from session memory. Change
        // the authoritative backend before confirmation; execution must calculate from the new value.
        SECOND_CARD_BALANCE.set("6,000.00");
        ChatResponseDto confirmed = chat(session, "确认执行转账");
        assertThat(confirmed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(confirmed.text()).contains("3000");
        assertThat(LAST_TRANSFER.get()).isNotNull();
        assertThat(LAST_TRANSFER.get().amount()).isEqualTo("3000");
        assertThat(LAST_TRANSFER.get().fromAccount()).isEqualTo("尾号 3344 借记卡");
    }

    @Test
    @DisplayName("查余额 -> 第二张呢 -> 上下文改写后查询第二个账户")
    void secondCardReferenceUsesContextualRewrite() {
        String session = "s-" + UUID.randomUUID();

        ChatResponseDto first = chat(session, "查一下余额");
        assertThat(first.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(first.text()).contains("8821");

        ChatResponseDto second = chat(session, "第二张呢");
        assertThat(second.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(second.decision().selectedCandidateId()).isEqualTo("cap.account.balance.query");
        assertThat(second.decision().evidenceRefs()).contains("fact:accounts");
        assertThat(second.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(second.text()).contains("3344").contains("8,000");
    }

    @Test
    @DisplayName("转账待确认 -> 更正收款人 -> 仍待确认 -> 执行新收款人")
    void correctionUpdatesPendingTransferWithoutExecuting() {
        String session = "s-" + UUID.randomUUID();

        ChatResponseDto review = chat(session, "给张三转1000");
        assertThat(review.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(review.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(review.text()).contains("张三");
        assertThat(LAST_TRANSFER.get()).isNull();

        ChatResponseDto corrected = chat(session, "不是张三，是李四");
        assertThat(corrected.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(corrected.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(corrected.plan().slots()).containsEntry("payee", "李四");
        assertThat(corrected.text()).contains("李四").doesNotContain("张三");
        assertThat(LAST_TRANSFER.get()).isNull();
        assertThat(taskRepository.findActiveBySession(agentIdentity.id(), session))
                .isPresent()
                .get()
                .extracting(t -> t.state())
                .isEqualTo(TaskState.CONFIRM_PENDING);

        ChatResponseDto confirmed = chat(session, "确认执行转账");
        assertThat(confirmed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(LAST_TRANSFER.get()).isNotNull();
        assertThat(LAST_TRANSFER.get().payee()).isEqualTo("李四");
        assertThat(LAST_TRANSFER.get().amount()).isEqualTo("1000");
    }

    @Test
    @DisplayName("信用卡账单缺卡 -> 自然语言补 cardRef -> 原任务完成")
    void creditcardNeedUserResumesWithModelResolvedCardReference() {
        String session = "s-" + UUID.randomUUID();

        ChatResponseDto missing = chat(session, "查信用卡账单");
        assertThat(missing.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(missing.decision().selectedCandidateId())
                .isEqualTo("cap.creditcard.bill.query");
        assertThat(missing.decision().missingSlots()).containsExactly("cardRef");
        assertThat(BILL_CALLS).hasValue(0);

        ChatResponseDto resumed = chat(session, "尾号8821那张");
        assertThat(resumed.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(resumed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(resumed.text()).contains("1,200");
        assertThat(LAST_BILL_CARD_REF).hasValue("opaque-card-8821");
        assertThat(BILL_CALLS).hasValue(1);
    }

    @Test
    @DisplayName("转账缺金额 -> 基金任务 -> 按钮或自然语言恢复，Runtime 槽位隔离且 taskId 不变")
    void transferSurvivesFundTaskSwitchAndBothResumeChannels() {
        runTransferFundSwitchScenario(true);
        runTransferFundSwitchScenario(false);
    }

    private void runTransferFundSwitchScenario(boolean resumeByButton) {
        String session = "s-" + UUID.randomUUID();
        chat(session, "查一下余额");

        ChatResponseDto transfer = chat(session, "用第二张卡给张三转账");
        String transferTaskId = transfer.taskId();
        assertThat(transfer.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(transfer.decision().missingSlots()).containsExactly("amount");
        assertThat(taskRepository.findById(transferTaskId).orElseThrow().parameters())
                .containsEntry("accountOrdinal", 2)
                .containsEntry("fromAccount", "尾号 3344 借记卡")
                .containsEntry("payee", "张三")
                .doesNotContainKey("amount");

        ChatResponseDto switchReview = chat(session, "先看看基金产品C", "finance-center");
        assertThat(switchReview.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.SWITCH_REVIEW);
        ResponseAction accept = switchReview.actions().stream()
                .filter(action -> "SWITCH_ACCEPT".equals(action.event())).findFirst().orElseThrow();
        ChatResponseDto fund = chat(session, "", "finance-center",
                new ActionEvent(accept.event(), accept.ref(), accept.version()));
        assertThat(fund.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(fund.taskId()).isNotBlank().isNotEqualTo(transferTaskId);
        assertThat(taskRepository.findById(fund.taskId()).orElseThrow().parameters())
                .doesNotContainKeys("accountOrdinal", "fromAccount", "payee", "amount");

        ChatResponseDto restored;
        if (resumeByButton) {
            ResponseAction resume = fund.actions().stream()
                    .filter(action -> "RESUME_SUSPENDED".equals(action.event()))
                    .filter(action -> transferTaskId.equals(action.ref()))
                    .findFirst().orElseThrow();
            restored = chat(session, "", "home",
                    new ActionEvent(resume.event(), resume.ref(), resume.version()));
        } else {
            restored = chat(session, "继续转账", "home");
        }
        assertThat(restored.decision().decision()).isEqualTo(Decision.RESUME_TASK);

        ChatResponseDto amount = chat(session, "1000");
        assertThat(amount.taskId()).isEqualTo(transferTaskId);
        assertThat(amount.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(amount.plan().slots())
                .containsEntry("accountOrdinal", 2)
                .containsEntry("fromAccount", "尾号 3344 借记卡")
                .containsEntry("payee", "张三")
                .containsEntry("amount", "1000");

        ChatResponseDto confirmed = chat(session, "确认执行转账");
        assertThat(confirmed.taskId()).isEqualTo(transferTaskId);
        assertThat(confirmed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(LAST_TRANSFER.get()).isNotNull();
        assertThat(LAST_TRANSFER.get().fromAccount()).isEqualTo("尾号 3344 借记卡");
        assertThat(LAST_TRANSFER.get().payee()).isEqualTo("张三");
        assertThat(LAST_TRANSFER.get().amount()).isEqualTo("1000");
    }

    private ChatResponseDto chat(String sessionId, String query) {
        return chat(sessionId, query, "home", null);
    }

    private ChatResponseDto chat(String sessionId, String query, String page) {
        return chat(sessionId, query, page, null);
    }

    private ChatResponseDto chat(String sessionId, String query, String page, ActionEvent action) {
        ChatRequestDto request = new ChatRequestDto(
                sessionId, "u-1", query, "MOBILE_BANK", page, "", action);
        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isNotNull();
        return entity.getBody();
    }

    private void assertRawHistoryVisibleToDownstreamModules(
            String sessionId, String query, String expectedHistoryText) {
        var entry = recentDecisions.snapshot().stream()
                .filter(item -> sessionId.equals(item.sessionId()) && query.equals(item.query()))
                .findFirst()
                .orElseThrow();
        List<String> modules = List.of(
                "context-engine", "intent-engine", "working-memory",
                "task-orchestrator", "response-engine");
        assertThat(entry.moduleSteps())
                .filteredOn(step -> modules.contains(step.module())
                        && !"compile-lease".equals(step.operation()))
                .extracting(step -> step.module())
                .containsAll(modules);
        assertThat(entry.moduleSteps())
                .filteredOn(step -> modules.contains(step.module())
                        && !"compile-lease".equals(step.operation()))
                .allSatisfy(step -> assertThat(String.valueOf(step.input().get("conversationHistory")))
                        .as(step.module() + " raw conversation history")
                        .contains(expectedHistoryText));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ContextBackends {
        @Bean
        @Primary
        AccountPort contextTestAccountPort() {
            return new AccountPort() {
                @Override public AccountView accountView(String principalRef) {
                    return new AccountView(List.of(
                            new CardView(1, "尾号 8821 借记卡", "12,845.60"),
                            new CardView(2, "尾号 3344 借记卡", SECOND_CARD_BALANCE.get()),
                            new CardView(3, "尾号 5566 信用卡", "3,000.00")));
                }
                @Override public List<TransactionView> transactions(String principalRef) {
                    return List.of();
                }
            };
        }

        @Bean
        @Primary
        TransferPort contextTestTransferPort() {
            return command -> {
                LAST_TRANSFER.set(command);
                return new TransferPort.TransferReceipt(command.payee(), command.amount(),
                        command.fromAccount(), "TR-CONTEXT-E2E", "2026-08-01T00:00:00Z");
            };
        }

        @Bean
        @Primary
        CreditcardPort contextTestCreditcardPort() {
            return new CreditcardPort() {
                @Override public BillView bill(String principalRef, String cardRef) {
                    BILL_CALLS.incrementAndGet();
                    LAST_BILL_CARD_REF.set(cardRef);
                    return new BillView("1,200.00", "2026-08-10");
                }
                @Override public OperationReceipt repay(RepayCommand command) {
                    return new OperationReceipt("RP-CONTEXT", command.amount(), "");
                }
                @Override public OperationReceipt replace(ReplaceCommand command) {
                    return new OperationReceipt("RC-CONTEXT", "", command.cardType());
                }
            };
        }

        @Bean
        @Primary
        FundProductPort contextTestFundProductPort() {
            return principalRef -> new FundProductPort.ProductView(
                    "FUND-C", "基金产品C", "fund_service", "R2", "3.20%", "12个月");
        }

        @Bean
        @Primary
        ModelGatewayClient contextContractModelGateway() {
            return new ContextContractModelGateway();
        }
    }

    /** Test-only model double. Production still uses the configured model gateway. */
    private static final class ContextContractModelGateway implements ModelGatewayClient {
        private static final Pattern STATE_VERSION = Pattern.compile("stateVersion=(\\d+)");
        private static final Pattern RUNTIME_REF = Pattern.compile("\\\"runtimeRef\\\":\\\"([^\\\"]+)\\\"");

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.ok(inputs.stream().map(ContextContractModelGateway::semanticVector).toList(), 1);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok(switch (request.purpose()) {
                case "context-rewrite" -> contextRewrite(request.userPrompt());
                case "continuation" -> continuation(request.userPrompt());
                default -> arbitration(request.userPrompt());
            }, 1);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            List<RerankHit> hits = java.util.stream.IntStream.range(0, documents.size())
                    .mapToObj(index -> new RerankHit(index, semanticScore(query, documents.get(index))))
                    .sorted(java.util.Comparator.comparingDouble(RerankHit::relevanceScore).reversed())
                    .limit(topN)
                    .toList();
            return GatewayResult.ok(hits, 1);
        }

        @Override public boolean available() { return true; }

        private static String contextRewrite(String prompt) {
            long version = Math.max(0, matchLong(STATE_VERSION, prompt));
            String original = lineValue(prompt, "originalQuery=");
            if (original.equals("第二张呢")) {
                return """
                        {"originalQuery":"第二张呢","standaloneQuery":"查询账户列表中第二个账户的余额",
                         "eventType":"SUPPLEMENT","resolutions":[{"mention":"第二张呢","contextRef":"fact:accounts",
                         "sourceTurnRef":null,"resolution":"账户列表中第二个账户","resolutionType":"ORDINAL_REFERENCE"}],
                         "usedContextRefs":["fact:accounts"],"unusedContextRefs":[],"slotUpdates":{"accountOrdinal":2},
                         "invalidatedContextRefs":[],"confidence":0.99,"reasonCode":"MODEL_REFERENCE","stateVersion":%d}
                        """.formatted(version);
            }
            if (original.equals("用第二张卡转一半给张三")) {
                return """
                        {"originalQuery":"用第二张卡转一半给张三","standaloneQuery":"使用第二个账户引用的最新可用余额一半转给张三",
                         "eventType":"NEW_PARALLEL_TASK","resolutions":[
                         {"mention":"第二张卡","contextRef":"fact:accounts","sourceTurnRef":null,
                          "resolution":"第二个账户","resolutionType":"ORDINAL_REFERENCE"},
                         {"mention":"一半","contextRef":"fact:balance-snapshot","sourceTurnRef":null,
                          "resolution":"执行前重查后计算","resolutionType":"REQUERY_THEN_HALF"}],
                         "usedContextRefs":["fact:accounts","fact:balance-snapshot"],"unusedContextRefs":[],
                         "slotUpdates":{"accountOrdinal":2,"amountBasis":"REQUERY_THEN_HALF"},
                         "invalidatedContextRefs":[],"confidence":0.99,
                         "reasonCode":"MODEL_REQUERY","stateVersion":%d}
                        """.formatted(version);
            }
            if (original.equals("用第二张卡给张三转账")) {
                return """
                        {"originalQuery":"用第二张卡给张三转账","standaloneQuery":"使用账户列表中第二个账户给张三转账",
                         "eventType":"NEW_PARALLEL_TASK","resolutions":[
                         {"mention":"第二张卡","contextRef":"fact:accounts","sourceTurnRef":null,
                          "resolution":"第二个账户","resolutionType":"ORDINAL_REFERENCE"}],
                         "usedContextRefs":["fact:accounts"],"unusedContextRefs":[],
                         "slotUpdates":{"accountOrdinal":2},"invalidatedContextRefs":[],
                         "confidence":0.99,"reasonCode":"MODEL_REFERENCE","stateVersion":%d}
                        """.formatted(version);
            }
            return """
                    {"originalQuery":"%s","standaloneQuery":"%s","eventType":"NEW_TASK",
                     "resolutions":[],"usedContextRefs":[],"unusedContextRefs":[],"slotUpdates":{},
                     "invalidatedContextRefs":[],"confidence":0.99,"reasonCode":"NO_CONTEXT_REQUIRED",
                     "stateVersion":%d}
                    """.formatted(original, original, version);
        }

        private static String continuation(String prompt) {
            String ref = match(RUNTIME_REF, prompt);
            String input = lineValue(prompt, "userInput=");
            if (input.equals("不是张三，是李四")) {
                return """
                        {"event":"CORRECTION","targetRef":"%s","slotUpdates":{"payee":"李四"},
                         "newGoalSpan":null,"confidence":0.99,"reasonCode":"MODEL_CORRECTION",
                         "confirmationStrength":"NONE"}
                        """.formatted(ref);
            }
            if (input.equals("尾号8821那张")) {
                return """
                        {"event":"FILL_SLOT","targetRef":"%s",
                         "slotUpdates":{"cardRef":"opaque-card-8821"},"newGoalSpan":null,
                         "confidence":0.99,"reasonCode":"MODEL_CARD_REFERENCE",
                         "confirmationStrength":"NONE"}
                        """.formatted(ref);
            }
            if (input.equals("确认执行转账")) {
                return """
                        {"event":"CONFIRM","targetRef":"%s","slotUpdates":{},
                         "newGoalSpan":null,"confidence":0.99,"reasonCode":"MODEL_EXPLICIT_CONFIRM",
                         "confirmationStrength":"EXPLICIT_ACTION"}
                        """.formatted(ref);
            }
            if (input.equals("继续转账")) {
                return """
                        {"event":"RESUME_SUSPENDED","targetRef":"%s","slotUpdates":{},
                         "newGoalSpan":null,"confidence":0.99,"reasonCode":"MODEL_TASK_REFERENCE",
                         "confirmationStrength":"NONE"}
                        """.formatted(ref);
            }
            if (input.equals("先看看基金产品C")) {
                return """
                        {"event":"SWITCH_TO_NEW_GOAL","targetRef":"%s","slotUpdates":{},
                         "newGoalSpan":"先看看基金产品C","confidence":0.99,
                         "reasonCode":"MODEL_NEW_GOAL","confirmationStrength":"NONE"}
                        """.formatted(ref);
            }
            if (input.equals("1000")) {
                return """
                        {"event":"FILL_SLOT","targetRef":"%s","slotUpdates":{"amount":"1000"},
                         "newGoalSpan":null,"confidence":0.99,"reasonCode":"MODEL_AMOUNT",
                         "confirmationStrength":"NONE"}
                        """.formatted(ref);
            }
            return """
                    {"event":"UNRESOLVED","targetRef":null,"slotUpdates":{},"newGoalSpan":null,
                     "confidence":0.0,"reasonCode":"AMBIGUOUS","confirmationStrength":"NONE"}
                    """;
        }

        private static String arbitration(String prompt) {
            String query = lineValue(prompt, "当前用户原话：");
            if (query.equals("用第二张卡给张三转账")) {
                return """
                        {"decision":"CLARIFY","taskShape":"SINGLE_ACTION",
                         "candidateIds":["cap.transfer"],"subGoals":[],"missingSlots":["amount"],
                         "extractedSlots":{"payee":"张三","accountOrdinal":2},"confidence":0.99,
                         "reasonCode":"MISSING_SLOT"}
                        """;
            }
            if (query.contains("基金产品C")) {
                return """
                        {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                         "candidateIds":["cap.fund.product.query"],"subGoals":[],"missingSlots":[],
                         "extractedSlots":{},"confidence":0.99,"reasonCode":"HIGH_CONFIDENCE"}
                        """;
            }
            if (query.contains("转") || query.contains("汇款")) {
                return """
                        {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                         "candidateIds":["cap.transfer"],"subGoals":[],"missingSlots":[],
                         "extractedSlots":{"payee":"张三","amount":"1000"},"confidence":0.99,
                         "reasonCode":"CONFIRMATION_REQUIRED"}
                        """;
            }
            if (query.contains("信用卡") && query.contains("账单")) {
                return """
                        {"decision":"CLARIFY","taskShape":"SINGLE_ACTION",
                         "candidateIds":["cap.creditcard.bill.query"],"subGoals":[],
                         "missingSlots":["cardRef"],"extractedSlots":{},"confidence":0.99,
                         "reasonCode":"MISSING_SLOT"}
                        """;
            }
            return """
                    {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                     "candidateIds":["cap.account.balance.query"],"subGoals":[],"missingSlots":[],
                     "extractedSlots":{},"confidence":0.99,"reasonCode":"HIGH_CONFIDENCE"}
                    """;
        }

        private static float[] semanticVector(String text) {
            float[] vector = new float[1024];
            vector[0] = .01f;
            if (text != null && (text.contains("余额") || text.contains("账户列表"))) vector[1] = 1;
            if (text != null && (text.contains("转") || text.contains("汇款") || text.contains("收款人"))) vector[2] = 1;
            if (text != null && text.contains("信用卡") && text.contains("账单")) vector[3] = 1;
            if (text != null && text.contains("基金") && text.contains("产品A")) vector[4] = 1;
            return vector;
        }

        private static double semanticScore(String query, String document) {
            double score = .1;
            if (query.contains("余额") && document.contains("余额")) score += .8;
            if ((query.contains("转") || query.contains("汇款"))
                    && (document.contains("转") || document.contains("汇款"))) score += .8;
            if (query.contains("信用卡") && query.contains("账单")
                    && document.contains("信用卡") && document.contains("账单")) score += .8;
            if (query.contains("基金") && query.contains("产品A")
                    && document.contains("基金") && document.contains("产品A")) score += .8;
            return score;
        }

        private static long matchLong(Pattern pattern, String input) {
            String value = match(pattern, input);
            return value.isBlank() ? -1 : Long.parseLong(value);
        }

        private static String match(Pattern pattern, String input) {
            var matcher = pattern.matcher(input == null ? "" : input);
            return matcher.find() ? matcher.group(1) : "";
        }

        private static String lineValue(String input, String prefix) {
            return (input == null ? "" : input).lines()
                    .filter(line -> line.startsWith(prefix))
                    .findFirst()
                    .map(line -> line.substring(prefix.length()))
                    .orElse("");
        }
    }
}
