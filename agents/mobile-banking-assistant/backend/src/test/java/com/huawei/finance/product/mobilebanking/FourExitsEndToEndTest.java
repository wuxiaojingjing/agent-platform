package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.ResponseAction;
import com.huawei.finance.contracts.model.ResponseComponent;
import com.huawei.finance.runtime.ActionEvent;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.domain.creditcard.CreditcardPort;
import com.huawei.finance.domain.account.AccountPort;
import com.huawei.finance.domain.transfer.TransferPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import java.util.stream.Stream;

/**
 * 端到端验收：v0.7 §3.8 的四条真实场景各命中一个出口。
 *
 * <p>走的是 REST 入口而不是直接调 {@code ChatService}：装配错了、Jackson 序列化不了枚举、
 * 别名没切过来，这些都只会在完整链路上暴露。
 *
 * <p>三个中间件全部为真。模型网关按环境变量决定是否可用，两种情况都必须给出同样的出口——
 * 模型不可用时走规则仲裁回退，这是设计内的分支而非故障。
 *
 * <p>中间件没起时整类跳过，与中控集成测试同一套约定：本地没开 Docker 不该让构建变红，
 * 但跳过必须是显式的。跳过不等于通过——这条在验收口径里是硬规则，
 * 发布门禁要单独确认这批用例真的跑过。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({FourExitsEndToEndTest.CreditcardTestConfiguration.class,
        ContinuationModelFixtureConfiguration.class})
@TestPropertySource(properties = "huawei.finance.cache.decision.enabled=true")
class FourExitsEndToEndTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String AGENT_ID = "agent.mobile-banking-assistant";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private IntentPlanRepository planRepository;

    /**
     * 在 Spring 上下文加载之前判断中间件在不在。
     *
     * <p>放 {@code @BeforeAll} 而不是 {@code @BeforeEach}：依赖注入发生在实例化阶段，
     * 等到 {@code @BeforeEach} 才 assume，上下文已经因为连不上 Redis 抛过异常了。
     */
    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过端到端验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过端到端验收");
    }

    /**
     * 探到协议层，不只看端口通不通。
     *
     * <p>Docker 挂掉之后，宿主机上的端口转发仍在监听，TCP 连接照样建得起来，
     * 而后面的每一次真实操作都会超时。只连端口的探活会给出「就绪」的错误结论，
     * 于是整批用例带着一个无法启动的上下文红掉，看起来像代码坏了。
     */
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
        // 出口缓存跨用例会互相影响：上一条用例留下的结论会让本条直接走一级短路，
        // 从而验不到真实的判定链路
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    @DisplayName("「查一下余额」→ EXECUTE_CAPABILITY，直接给出余额")
    void balanceQueryFastExecutes() {
        ChatResponseDto response = chat("查一下余额");

        assertThat(response.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(response.decision().selectedCandidateId()).isEqualTo("cap.account.balance.query");
        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(response.plan().cardComponents()).contains(ResponseComponent.RESULT_SUMMARY);
        assertThat(response.fellBack()).isFalse();
        assertThat(response.text()).contains("可用余额");
        assertThat(response.taskId()).isNotBlank();
    }

    @Test
    @DisplayName("「换卡」→ CLARIFY，只问卡种一个问题")
    void cardReplaceClarifies() {
        ChatResponseDto response = chat("换卡");

        assertThat(response.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.MISSING_SLOT);
        assertThat(response.decision().missingSlots()).containsExactly("cardType");
        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(response.plan().cardComponents()).contains(ResponseComponent.CHOICE_LIST);
        assertThat(response.text()).contains("信用卡").contains("借记卡");
    }

    @Test
    @DisplayName("「查余额，再给老徐转 1000；不足就别转」→ STATIC_PLAN + RESULT_RULE")
    void multiTaskGoesSlowPath() {
        String session = "s-" + UUID.randomUUID();
        ChatResponseDto response = chat(session, "查余额，再给老徐转 1000；不足就别转", "home");

        assertThat(response.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);
        assertThat(response.decision().taskShape())
                .isEqualTo(com.huawei.finance.contracts.model.TaskShape.CONDITIONAL_PLAN);
        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(response.plan().cardComponents()).contains(
                ResponseComponent.TASK_PROGRESS,
                ResponseComponent.REVIEW_SUMMARY,
                ResponseComponent.RISK_NOTICE);
        assertThat(response.usedTemplate()).isEqualTo("tpl.transfer.confirm");
        assertThat(response.taskId()).isNotBlank();
        assertThat(response.text()).contains("老徐").contains("1000");
        assertThat(response.text())
                .as("「不足就别转」是转账的条件，不是第三件待办")
                .doesNotContain("别转");

        ResponseAction confirm = response.actions().stream()
                .filter(action -> "CONFIRM".equals(action.event())).findFirst().orElseThrow();
        var waiting = planRepository.findById(AGENT_ID, confirm.ref()).orElseThrow();
        assertThat(waiting.state()).isEqualTo(PlanState.WAITING_CONFIRMATION);
        assertThat(waiting.cursor()).isEqualTo(1);
        assertThat(confirm.version()).isEqualTo(waiting.stateVersion());
        assertThat(planRepository.steps(waiting.planId()))
                .extracting(com.huawei.finance.orchestrator.plan.PlanStepRecord::capabilityId)
                .contains("cap.account.balance.query");
        assertThat(taskRepository.idempotencyKeyOf(response.taskId())).isEmpty();

        ChatResponseDto ambiguous = chat(session, "确认", "home");
        assertThat(ambiguous.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(taskRepository.idempotencyKeyOf(response.taskId())).isEmpty();

        ChatResponseDto completed = chat(session, "确认执行转账", "home");
        assertThat(completed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(planRepository.findById(AGENT_ID, waiting.planId()).orElseThrow().state())
                .isEqualTo(PlanState.COMPLETED);
        assertThat(taskRepository.findById(response.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    @DisplayName("固定条件：工资没到账就检查工资卡状态 → STATIC_PLAN + RESULT_RULE")
    void payrollConditionIsStaticPlan() {
        ChatResponseDto response = chat("工资没到账，就检查工资卡状态");

        assertThat(response.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(response.decision().taskShape())
                .isEqualTo(com.huawei.finance.contracts.model.TaskShape.CONDITIONAL_PLAN);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);
        assertThat(response.decision().intentPlan()).isNotNull();
        assertThat(response.decision().intentPlan().fullyResolved()).isTrue();
        assertThat(response.decision().intentPlan().items()).extracting(
                        com.huawei.finance.contracts.model.SubIntent::capabilityId)
                .containsExactly("cap.payroll.arrival.query", "cap.account.card.status.query");
    }

    @Test
    @DisplayName("显式 Workflow 走 START_WORKFLOW；领域内部流程能力仍走 EXECUTE_CAPABILITY")
    void explicitWorkflowAndCapabilityInternalWorkflowAreDistinct() {
        ChatResponseDto explicit = chat("启动信用卡换卡流程");

        assertThat(explicit.decision().decision()).isEqualTo(Decision.START_WORKFLOW);
        assertThat(explicit.decision().selectedCandidateId()).isEqualTo("workflow.creditcard.replace");
        assertThat(explicit.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.REVIEW);

        ChatResponseDto capability = chat("我要换信用卡");

        assertThat(capability.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(capability.decision().selectedCandidateId()).isEqualTo("cap.card.replace");
        assertThat(capability.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.REVIEW);
    }

    @Test
    @DisplayName("「帮我把信用卡额度改成 10 万」→ HANDOFF，说明未开放并给替代入口")
    void creditLimitAdjustRejected() {
        ChatResponseDto response = chat("帮我把信用卡额度改成 10 万");

        assertThat(response.decision().decision()).isEqualTo(Decision.HANDOFF);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
        // 策略拦截走二级强规则，不该动模型
        assertThat(response.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L2_STRONG_RULE);
        assertThat(response.usedTemplate()).isEqualTo("tpl.reject.capability-not-open");
        assertThat(response.text()).contains("信用卡额度调整").contains("额度管理");
    }

    @Test
    @DisplayName("未建设债券 Agent 不执行交易，降级到储蓄国债菜单")
    void scaffoldAgentFallsBackToConfiguredNavigation() {
        ChatResponseDto response = chat("我要购买储蓄国债");

        assertThat(response.decision().decision()).isEqualTo(Decision.NAVIGATION);
        assertThat(response.decision().selectedCandidateId())
                .isEqualTo("cap.nav.bond_service_储蓄国债");
        assertThat(response.taskId()).isNull();
        assertThat(response.plan().actionCodes()).containsExactly("OPEN_MENU");
        assertThat(response.plan().cardComponents()).contains(ResponseComponent.NAVIGATION);
        assertThat(response.plan().slots()).containsEntry("menuName", "储蓄国债");
        assertThat(response.text()).contains("储蓄国债");
    }

    @ParameterizedTest(name = "小 i 内化知识：{0}")
    @MethodSource("xiaoiApprovedCases")
    @DisplayName("首批六条小 i 审批知识经真实入口直接回答")
    void approvedXiaoiKnowledgeRunsThroughChat(String query, String expectedText,
                                                String expectedEvidence) {
        ChatResponseDto response = chat(query);

        assertThat(response.decision().decision()).isEqualTo(Decision.DIRECT_KNOWLEDGE);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.STANDARD_ANSWER);
        assertThat(response.decision().shortCircuit())
                .isEqualTo(ShortCircuitLevel.STANDARD_ANSWER_RULE);
        assertThat(response.decision().evidenceRefs()).containsExactly(expectedEvidence);
        assertThat(response.taskId()).isNull();
        assertThat(response.text()).contains(expectedText);
    }

    private static Stream<Arguments> xiaoiApprovedCases() {
        return Stream.of(
                Arguments.of("遇到26023怎么办", "不要反复尝试", "standardQa:qa.transfer.error.26023"),
                Arguments.of("如何开通换卡无忧", "智能终端补开", "standardQa:qa.card.replacement-care.open"),
                Arguments.of("怎么调整存折账户属性", "多币种账户", "standardQa:qa.passbook.account-attribute.adjust"),
                Arguments.of("如何通过网银销户活期存折", "小额账户销户", "standardQa:qa.passbook.close.online-banking"),
                Arguments.of("储蓄卡换卡换号会影响哪些业务", "第三方存管", "standardQa:qa.card.renumber.impact"),
                Arguments.of("什么是换卡无忧", "自动迁移到新卡", "standardQa:qa.card.replacement-care.intro"));
    }

    @ParameterizedTest(name = "截图知识：{0}")
    @MethodSource("screenshotKnowledgeCases")
    @DisplayName("手机银行目录与网点预约截图知识经真实入口回答并携带来源")
    void screenshotKnowledgeRunsThroughChat(String query, String expectedText,
                                             String qaEvidence, String sourceEvidence) {
        ChatResponseDto response = chat(query);

        assertThat(response.decision().decision()).isEqualTo(Decision.DIRECT_KNOWLEDGE);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.STANDARD_ANSWER);
        assertThat(response.decision().evidenceRefs()).containsExactly(qaEvidence, sourceEvidence);
        assertThat(response.taskId()).isNull();
        assertThat(response.text()).contains(expectedText);
        assertThat(response.plan().cardComponents()).contains(ResponseComponent.MENU_LIST);
        assertThat(response.plan().slots()).containsKey("menuItems");
    }

    private static Stream<Arguments> screenshotKnowledgeCases() {
        return Stream.of(
                Arguments.of("手机银行贷款服务包括哪些功能", "信用报告",
                        "standardQa:qa.mobile-banking.business-directory.loan",
                        "screenshotLedger:mobile-banking-business-directory:image-3"),
                Arguments.of("账户管理里有什么功能", "电子工资单",
                        "standardQa:qa.mobile-banking.business-directory.account",
                        "screenshotLedger:mobile-banking-business-directory:image-1"),
                Arguments.of("网点预约支持哪些业务", "一次至多选择2项",
                        "standardQa:qa.branch.appointment.supported-businesses",
                        "screenshotLedger:branch-appointment:image-5"));
    }

    @ParameterizedTest(name = "目录知识不截获执行请求：{0}")
    @MethodSource("businessDirectoryNegativeControls")
    void businessDirectoryKnowledgeDoesNotCaptureExecutionIntents(String query) {
        ChatResponseDto response = chat(query);

        assertThat(response.decision().reasonCode()).isNotEqualTo(ReasonCode.STANDARD_ANSWER);
        assertThat(response.decision().evidenceRefs()).noneMatch(ref ->
                ref.startsWith("screenshotLedger:"));
    }

    private static Stream<String> businessDirectoryNegativeControls() {
        return Stream.of("帮我转账", "我要存款", "查余额");
    }

    @Test
    @DisplayName("小 i 损坏来源只给安全知识选项，不误建换卡任务")
    void blockedXiaoiKnowledgeOffersSafeChoices() {
        ChatResponseDto response = chat("换卡不换号");

        assertThat(response.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
        assertThat(response.decision().shortCircuit())
                .isEqualTo(ShortCircuitLevel.STANDARD_ANSWER_RULE);
        assertThat(response.decision().evidenceRefs()).containsExactly(
                "standardQaBlocked:qa.card.same-number.blocked-guidance",
                "xiaoiLedger:reviewed-workbook:standard-question:15");
        assertThat(response.taskId()).isNull();
        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(response.plan().cardComponents()).contains(ResponseComponent.CHOICE_LIST);
        assertThat(response.plan().slots()).containsEntry("options", List.of(
                "了解换卡无忧", "了解换卡换号影响", "办理换卡"));
        assertThat(response.plan().slots()).doesNotContainKey("payee");
        assertThat(response.text()).contains("无法根据已复核知识确认");
    }

    @Test
    @DisplayName("同一句话第二次命中一级出口缓存，短路层级可见")
    void secondIdenticalRequestHitsCache() {
        ChatResponseDto first = chat("看看我的理财持仓");
        ChatResponseDto second = chat("看看我的理财持仓");

        assertThat(first.decision().shortCircuit()).isNotEqualTo(ShortCircuitLevel.L1_CACHE);
        assertThat(second.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L1_CACHE);
        // 命中缓存不改写「当初为什么是这个出口」
        assertThat(second.decision().decision()).isEqualTo(first.decision().decision());
        assertThat(second.decision().reasonCode()).isEqualTo(first.decision().reasonCode());
    }

    @Test
    @DisplayName("转账进 CONFIRM_PENDING，确认前查不到幂等凭据；确认后才执行")
    void transferRequiresConfirmationBeforeExecution() {
        String session = "s-" + UUID.randomUUID();

        ChatResponseDto pending = chat(session, "给张三转 1000", "transfer");

        assertThat(pending.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(pending.decision().reasonCode()).isEqualTo(ReasonCode.CONFIRMATION_REQUIRED);
        assertThat(pending.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(pending.plan().actionCodes()).contains("CONFIRM", "CANCEL");
        assertThat(pending.plan().cardComponents()).contains(
                ResponseComponent.REVIEW_SUMMARY, ResponseComponent.RISK_NOTICE);
        assertThat(pending.text()).contains("张三").contains("1000");

        var task = taskRepository.findById(pending.taskId()).orElseThrow();
        assertThat(task.state()).isEqualTo(TaskState.CONFIRM_PENDING);
        assertThat(taskRepository.idempotencyKeyOf(task.taskId())).isEmpty();

        ChatResponseDto ambiguous = chat(session, "确认", "transfer");

        assertThat(ambiguous.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(taskRepository.findById(task.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.CONFIRM_PENDING);
        assertThat(taskRepository.idempotencyKeyOf(task.taskId())).isEmpty();

        ChatResponseDto executed = chat(session, "确认执行转账", "transfer");

        assertThat(executed.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.CONTINUATION);
        assertThat(executed.decision().reasonCode()).isEqualTo(ReasonCode.CONTINUATION);
        assertThat(executed.taskId()).isEqualTo(task.taskId());
        assertThat(executed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(executed.text()).contains("张三").contains("1000");
        assertThat(taskRepository.idempotencyKeyOf(task.taskId())).isPresent();
        assertThat(taskRepository.findById(task.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    @DisplayName("换卡三轮：缺卡种澄清，补槽后 Review，继续后才执行")
    void cardReplacementClarifyReviewThenExecutes() {
        String session = "s-" + UUID.randomUUID();

        ChatResponseDto clarify = chat(session, "换卡", "home");
        assertThat(clarify.decision().decision()).isEqualTo(Decision.CLARIFY);

        ChatResponseDto review = chat(session, "信用卡", "home");

        assertThat(review.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(review.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.CONTINUATION);
        assertThat(review.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.REVIEW);
        assertThat(review.plan().cardComponents()).contains(ResponseComponent.REVIEW_SUMMARY);
        assertThat(review.text()).contains("信用卡");
        assertThat(review.actions()).extracting(com.huawei.finance.contracts.model.ResponseAction::event)
                .contains("REVIEW_ACCEPT", "CANCEL");
        var pending = taskRepository.findById(review.taskId()).orElseThrow();
        assertThat(pending.state()).isEqualTo(TaskState.REVIEW_PENDING);
        assertThat(taskRepository.idempotencyKeyOf(pending.taskId())).isEmpty();

        ChatResponseDto executed = chat(session, "继续", "home");

        assertThat(executed.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.CONTINUATION);
        assertThat(executed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(executed.text()).contains("信用卡");
        assertThat(taskRepository.findById(pending.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);
        assertThat(taskRepository.idempotencyKeyOf(pending.taskId())).isPresent();
    }

    @Test
    @DisplayName("换卡 Review 按钮携带真实 Task 版本；正确版本执行，过期版本不改变任务")
    void cardReplacementStructuredReviewIsVersioned() {
        String staleSession = "s-" + UUID.randomUUID();
        chat(staleSession, "换卡", "home");
        ChatResponseDto staleReview = chat(staleSession, "信用卡", "home");
        ResponseAction staleAction = staleReview.actions().stream()
                .filter(action -> "REVIEW_ACCEPT".equals(action.event())).findFirst().orElseThrow();
        assertThat(staleAction.version()).isPositive();

        ChatResponseDto rejected = chat(staleSession, "", "home",
                new ActionEvent(staleAction.event(), staleAction.ref(), staleAction.version() - 1));

        assertThat(rejected.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(rejected.decision().reasonCode()).isEqualTo(ReasonCode.RESUME_REQUIRED);
        assertThat(taskRepository.findById(staleAction.ref()).orElseThrow().state())
                .isEqualTo(TaskState.REVIEW_PENDING);
        assertThat(taskRepository.idempotencyKeyOf(staleAction.ref())).isEmpty();
        assertThat(rejected.actions()).extracting(ResponseAction::version)
                .contains(staleAction.version());

        ResponseAction latest = rejected.actions().stream()
                .filter(action -> "REVIEW_ACCEPT".equals(action.event())).findFirst().orElseThrow();
        ChatResponseDto executed = chat(staleSession, "", "home",
                new ActionEvent(latest.event(), latest.ref(), latest.version()));

        assertThat(executed.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(executed.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(taskRepository.findById(latest.ref()).orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    @DisplayName("换卡中查账单：确认切换后旧任务挂起，账单完成后可按真实版本恢复")
    void switchToBillQueryThenResumeCardReplacement() {
        String session = "s-" + UUID.randomUUID();
        ChatResponseDto clarify = chat(session, "换卡", "home");
        String cardTaskId = clarify.taskId();

        ChatResponseDto switchReview = chat(session, "先查信用卡账单", "home");

        assertThat(switchReview.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.SWITCH_REVIEW);
        ResponseAction accept = switchReview.actions().stream()
                .filter(action -> "SWITCH_ACCEPT".equals(action.event())).findFirst().orElseThrow();
        assertThat(taskRepository.findById(cardTaskId).orElseThrow().state())
                .isEqualTo(TaskState.CLARIFY_PENDING);

        ChatResponseDto billClarify = chat(session, "", "home",
                new ActionEvent(accept.event(), accept.ref(), accept.version()));

        assertThat(billClarify.decision().selectedCandidateId()).isEqualTo("cap.creditcard.bill.query");
        assertThat(billClarify.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        ChatResponseDto bill = chat(session, "尾号8821那张", "home");
        assertThat(bill.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(taskRepository.findById(cardTaskId).orElseThrow().state())
                .as("切换只改变平台焦点，旧 Task Runtime 仍保持原状态")
                .isEqualTo(TaskState.CLARIFY_PENDING);
        ResponseAction resume = bill.actions().stream()
                .filter(action -> "RESUME_SUSPENDED".equals(action.event())).findFirst().orElseThrow();
        assertThat(resume.ref()).isEqualTo(cardTaskId);
        assertThat(resume.version()).isPositive();

        ChatResponseDto restored = chat(session, "", "home",
                new ActionEvent(resume.event(), resume.ref(), resume.version()));

        assertThat(restored.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(restored.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.ACK);
        ChatResponseDto cardReview = chat(session, "信用卡", "home");
        assertThat(cardReview.taskId()).isEqualTo(cardTaskId);
        assertThat(cardReview.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.REVIEW);
    }

    @Test
    @DisplayName("拒绝切换不取消当前任务，后续仍可补槽")
    void rejectSwitchKeepsCurrentTask() {
        String session = "s-" + UUID.randomUUID();
        ChatResponseDto clarify = chat(session, "换卡", "home");
        ChatResponseDto switchReview = chat(session, "先查信用卡账单", "home");
        ResponseAction reject = switchReview.actions().stream()
                .filter(action -> "SWITCH_REJECT".equals(action.event())).findFirst().orElseThrow();

        ChatResponseDto rejected = chat(session, "", "home",
                new ActionEvent(reject.event(), reject.ref(), reject.version()));

        assertThat(rejected.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(taskRepository.findById(clarify.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.CLARIFY_PENDING);
        ChatResponseDto review = chat(session, "信用卡", "home");
        assertThat(review.taskId()).isEqualTo(clarify.taskId());
        assertThat(review.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.REVIEW);
    }

    @Test
    @DisplayName("PendingSwitch 接受和拒绝都支持自然语言，并与按钮共用状态校验")
    void pendingSwitchSupportsNaturalLanguageDecisions() {
        String acceptSession = "s-" + UUID.randomUUID();
        ChatResponseDto card = chat(acceptSession, "换卡", "home");
        chat(acceptSession, "先查信用卡账单", "home");

        ChatResponseDto bill = chat(acceptSession, "好，切换", "home");

        assertThat(bill.decision().selectedCandidateId()).isEqualTo("cap.creditcard.bill.query");
        assertThat(taskRepository.findById(card.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.CLARIFY_PENDING);

        String rejectSession = "s-" + UUID.randomUUID();
        ChatResponseDto current = chat(rejectSession, "换卡", "home");
        chat(rejectSession, "先查信用卡账单", "home");

        ChatResponseDto rejected = chat(rejectSession, "继续当前任务", "home");

        assertThat(rejected.decision().decision()).isEqualTo(Decision.RESUME_TASK);
        assertThat(taskRepository.findById(current.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.CLARIFY_PENDING);
    }

    @Test
    @DisplayName("多个挂起任务时只说继续，返回选择而不自动恢复")
    void multipleSuspendedTasksRequireSelection() {
        String session = "s-" + UUID.randomUUID();
        ChatResponseDto card = chat(session, "换卡", "home");

        ChatResponseDto toTransfer = chat(session, "先给张三转 1000", "transfer");
        ResponseAction acceptTransfer = toTransfer.actions().stream()
                .filter(action -> "SWITCH_ACCEPT".equals(action.event())).findFirst().orElseThrow();
        ChatResponseDto transfer = chat(session, "", "transfer",
                new ActionEvent(acceptTransfer.event(), acceptTransfer.ref(), acceptTransfer.version()));
        assertThat(transfer.plan().responsePhase()).isIn(
                Enums.ResponsePhase.CLARIFY, Enums.ResponsePhase.CONFIRM);

        ChatResponseDto toBill = chat(session, "先查信用卡账单", "home");
        ResponseAction acceptBill = toBill.actions().stream()
                .filter(action -> "SWITCH_ACCEPT".equals(action.event())).findFirst().orElseThrow();
        ChatResponseDto billClarify = chat(session, "", "home", new ActionEvent(
                acceptBill.event(), acceptBill.ref(), acceptBill.version()));
        assertThat(billClarify.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        ChatResponseDto bill = chat(session, "尾号8821那张", "home");
        assertThat(bill.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);

        ChatResponseDto choose = chat(session, "继续", "home");

        assertThat(choose.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(choose.decision().reasonCode()).isEqualTo(ReasonCode.RESUME_REQUIRED);
        assertThat(choose.actions()).filteredOn(action -> "RESUME_SUSPENDED".equals(action.event()))
                .extracting(ResponseAction::ref)
                .containsExactlyInAnyOrder(card.taskId(), transfer.taskId());
        assertThat(choose.plan().cardComponents()).contains(ResponseComponent.CHOICE_LIST);
        assertThat(taskRepository.findById(card.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.CLARIFY_PENDING);
        assertThat(taskRepository.findById(transfer.taskId()).orElseThrow().state())
                .isIn(TaskState.CLARIFY_PENDING, TaskState.CONFIRM_PENDING);
    }

    @Test
    @DisplayName("入口返回结构化出口，不只是一句文本")
    void responseCarriesFullContract() {
        ResponseEntity<ChatResponseDto> entity = post(new ChatRequestDto(
                "s-" + UUID.randomUUID(), "u-1", "查一下余额", "MOBILE_BANK", "home", ""));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        // traceId 取自 OTEL span：32 位十六进制、不带自造前缀。带前缀说明桥接掉线了，
        // 那意味着这个 id 在行内 APM 里查不到，排障时等于没给线索
        assertThat(body.traceId()).matches("[0-9a-f]{32}");
        assertThat(body.decision().configVersion()).startsWith("assets-v");
        assertThat(body.plan().templateKey()).isNotBlank();
        assertThat(body.plan().templateVersion()).isNotEqualTo("unknown");
    }

    private ChatResponseDto chat(String query) {
        return chat("s-" + UUID.randomUUID(), query, "home");
    }

    private ChatResponseDto chat(String sessionId, String query, String page) {
        return chat(sessionId, query, page, null);
    }

    private ChatResponseDto chat(String sessionId, String query, String page, ActionEvent action) {
        ResponseEntity<ChatResponseDto> entity = post(
                new ChatRequestDto(sessionId, "u-1", query, "MOBILE_BANK", page, "", action));
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    /** 带上渠道网关会注入的租户头（FP-65）：不带头的请求在入口就被 400 拦掉。 */
    private ResponseEntity<ChatResponseDto> post(ChatRequestDto request) {
        return rest.postForEntity("http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);
    }

    @TestConfiguration
    static class CreditcardTestConfiguration {
        @Bean
        @Primary
        AccountPort accountPort() {
            return new AccountPort() {
                @Override public AccountView accountView(String principalRef) {
                    return new AccountView(List.of(new CardView(1, "工资卡", "12845.60")));
                }

                @Override public List<TransactionView> transactions(String principalRef) {
                    return List.of();
                }
            };
        }

        @Bean
        @Primary
        TransferPort transferPort() {
            return command -> new TransferPort.TransferReceipt(
                    command.payee(), command.amount(), "工资卡", "transfer-test", "2026-08-01T00:00:00Z");
        }

        @Bean
        @Primary
        CreditcardPort creditcardPort() {
            return new CreditcardPort() {
                @Override public BillView bill(String principalRef, String cardRef) {
                    return new BillView("128.00", "2026-08-15");
                }
                @Override public OperationReceipt repay(RepayCommand command) {
                    return new OperationReceipt("repay-test", command.amount(), null);
                }
                @Override public OperationReceipt replace(ReplaceCommand command) {
                    String name = "CREDIT".equals(command.cardType()) ? "信用卡" : "借记卡";
                    return new OperationReceipt("replace-test", null, name);
                }
            };
        }
    }
}
