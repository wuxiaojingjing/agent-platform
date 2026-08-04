package com.huawei.finance.sample.workflow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用叶子操作。
 *
 * <p>它们同时是给行内看的写法样例：每个操作只做一件事，失败就抛，
 * 幂等以中控下发的键为准——尤其是 {@link SubmitTransfer}，那是「写操作怎么做幂等」的示范。
 */
final class StubOperations {

    private StubOperations() {
    }

    /** 记录谁被调过，用来验证条件不成立的步骤是真的没跑。 */
    static final class CallLog {

        private final List<String> calls = new ArrayList<>();

        void record(String name) {
            calls.add(name);
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }

    static final class ResolveDefaultAccount implements DomainOperation {

        private final CallLog log;
        private final RuntimeException failure;

        ResolveDefaultAccount(CallLog log) {
            this(log, null);
        }

        ResolveDefaultAccount(CallLog log, RuntimeException failure) {
            this.log = log;
            this.failure = failure;
        }

        @Override
        public String name() {
            return "payment.resolveDefaultAccount";
        }

        @Override
        public Map<String, Object> execute(OperationContext ctx) {
            log.record(name());
            if (failure != null) {
                throw failure;
            }
            return Map.of("accountNo", "6222***8821", "accountName", "尾号 8821 借记卡");
        }
    }

    static final class CheckLimit implements DomainOperation {

        private final CallLog log;
        private final BigDecimal limit;

        CheckLimit(CallLog log, BigDecimal limit) {
            this.log = log;
            this.limit = limit;
        }

        @Override
        public String name() {
            return "payment.checkLimit";
        }

        @Override
        public Map<String, Object> execute(OperationContext ctx) {
            log.record(name());
            BigDecimal amount = new BigDecimal(String.valueOf(ctx.params().getOrDefault("amount", "0")));
            if (amount.compareTo(limit) > 0) {
                // 业务规则拒绝：显式声明为 FATAL，重放不会有不同结果
                throw StepFailure.fatal("超出本行单笔限额 " + limit);
            }
            return Map.of("withinLimit", true, "remaining", limit.subtract(amount).toPlainString());
        }
    }

    /** 写操作：以幂等键去重，同一把键第二次来返回第一次的结果，不再动账。 */
    static final class SubmitTransfer implements DomainOperation {

        private final CallLog log;
        private final Map<String, Map<String, Object>> submitted = new ConcurrentHashMap<>();
        private final BigDecimal noticeThreshold;

        SubmitTransfer(CallLog log, BigDecimal noticeThreshold) {
            this.log = log;
            this.noticeThreshold = noticeThreshold;
        }

        int submissions() {
            return submitted.size();
        }

        @Override
        public String name() {
            return "payment.submitTransfer";
        }

        @Override
        public Map<String, Object> execute(OperationContext ctx) {
            log.record(name());
            return submitted.computeIfAbsent(ctx.idempotencyKey(), key -> {
                BigDecimal amount = new BigDecimal(String.valueOf(ctx.params().getOrDefault("amount", "0")));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("serialNo", "TR" + Integer.toHexString(key.hashCode()));
                result.put("noticeRequired", amount.compareTo(noticeThreshold) > 0);
                return Map.copyOf(result);
            });
        }
    }

    static final class SendNotice implements DomainOperation {

        private final CallLog log;

        SendNotice(CallLog log) {
            this.log = log;
        }

        @Override
        public String name() {
            return "payment.sendNotice";
        }

        @Override
        public Map<String, Object> execute(OperationContext ctx) {
            log.record(name());
            return Map.of("noticeId", "NT-" + ctx.idempotencyKey().hashCode());
        }
    }

    /** 缺信息，要回去问用户。 */
    static final class NeedsPayeeConfirmation implements DomainOperation {

        @Override
        public String name() {
            return "payment.confirmPayee";
        }

        @Override
        public Map<String, Object> execute(OperationContext ctx) {
            throw StepFailure.needUser("收款人有多个同名账户，需用户指定");
        }
    }

    static List<DomainOperation> all(CallLog log, BigDecimal limit, BigDecimal noticeThreshold) {
        return List.of(new ResolveDefaultAccount(log), new CheckLimit(log, limit),
                new SubmitTransfer(log, noticeThreshold), new SendNotice(log));
    }
}
