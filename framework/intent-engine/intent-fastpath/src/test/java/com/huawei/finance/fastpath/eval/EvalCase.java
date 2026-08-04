package com.huawei.finance.fastpath.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 一条评测用例（FP-52）。
 *
 * <p>用 YAML 外置而不是写在 Java 里，是为了让业务部能在不碰代码的前提下扩充与校订标注——
 * 计划 §5.5 把「框架自包含、标注需要业务部」拆成两件事，正是因为让业务方改 Java 数组
 * 等于让这件事停在工程手里。
 *
 * <p><b>未写的字段不校验。</b>只填 {@code decision} 的用例就只锁出口，不会顺带把当下的
 * reasonCode 和候选也变成契约。这条很要紧：把「恰好如此」的现状写进期望，
 * 日后正常的调优都会红一片，而人会开始改期望而不是看结论。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

    private String id = "";
    private String query = "";
    private String page = "home";
    private String userState = "loginStatus=LOGGED_IN";
    private Expect expect = new Expect();
    private Expect truth;
    private String labeledBy = LabelSource.ENGINEERING;
    private String status = Status.LOCKED;
    private String note = "";

    /**
     * 期望。所有字段都可缺省，缺省即不校验。
     *
     * <p>{@code missingSlots} 有第三态：不写=不校验，写空列表=断言必须为空。
     * 少了这个区分，「澄清但不该缺任何槽」这类反例没法表达。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Expect {
        private String decision;
        private String reasonCode;
        private String capability;
        private List<String> missingSlots;
        private String shortCircuit;
        private String templateKey;
        private String routingMode;
        private Map<String, String> slots = Map.of();

        public String getDecision() {
            return decision;
        }

        public void setDecision(String decision) {
            this.decision = decision;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public void setReasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        public String getCapability() {
            return capability;
        }

        public void setCapability(String capability) {
            this.capability = capability;
        }

        public List<String> getMissingSlots() {
            return missingSlots;
        }

        public void setMissingSlots(List<String> missingSlots) {
            this.missingSlots = missingSlots;
        }

        public String getShortCircuit() {
            return shortCircuit;
        }

        public void setShortCircuit(String shortCircuit) {
            this.shortCircuit = shortCircuit;
        }

        public String getTemplateKey() {
            return templateKey;
        }

        public void setTemplateKey(String templateKey) {
            this.templateKey = templateKey;
        }

        public String getRoutingMode() {
            return routingMode;
        }

        public void setRoutingMode(String routingMode) {
            this.routingMode = routingMode;
        }

        public Map<String, String> getSlots() {
            return slots;
        }

        public void setSlots(Map<String, String> slots) {
            this.slots = slots == null ? Map.of() : Map.copyOf(slots);
        }
    }

    /** 标注来源。区分它的唯一目的见 {@link EvalReport}：防止工程自己写的期望被当成效果结论。 */
    public static final class LabelSource {
        /** 工程按当前实现写下的现状锁。只能证明「没变」，不能证明「对」。 */
        public static final String ENGINEERING = "engineering";
        /** 业务部或合规签署的真值。只有这一类可以支撑效果口径。 */
        public static final String BUSINESS = "business";

        private LabelSource() {
        }
    }

    /**
     * 用例状态。
     *
     * <p>区分它的理由是：把「现状不对但先记下来」和「现状就是我们要的」混在同一个绿色里，
     * 通过率就会变成一个自我安慰的数字。两者都要断言（否则悄悄变了没人知道），
     * 但在报告里必须分开数，且 {@link #KNOWN_GAP} 永远不计入通过。
     */
    public static final class Status {
        /** 现状即期望，改动它需要理由。 */
        public static final String LOCKED = "locked";
        /**
         * 现状不是我们要的结论，但先钉住，免得它在无人察觉时又变一次。
         *
         * <p>这类用例的 {@code note} 必须写清缺口是什么——否则日后没人分得清
         * 它是「待修」还是「已认可」。
         */
        public static final String KNOWN_GAP = "known-gap";

        private Status() {
        }
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null || status.isBlank() ? Status.LOCKED : status;
    }

    public boolean knownGap() {
        return Status.KNOWN_GAP.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getPage() {
        return page;
    }

    public void setPage(String page) {
        this.page = page == null ? "home" : page;
    }

    public String getUserState() {
        return userState;
    }

    public void setUserState(String userState) {
        this.userState = userState == null ? "" : userState;
    }

    public Expect getExpect() {
        return expect;
    }

    public void setExpect(Expect expect) {
        this.expect = expect == null ? new Expect() : expect;
    }

    /**
     * 跨配置真值：**应该**是什么结果，与跑在哪档无关。
     *
     * <p>与 {@link #getExpect()} 分开是这份集子最要紧的一处区分：
     *
     * <ul>
     *   <li>{@code expect} 记的是降级态实测现状，作用是「变了要有人知道」。
     *       它可以是错的——`known-gap` 那几条就是明知错还钉住。</li>
     *   <li>{@code truth} 记的是「对」。它是诊断（召回问题还是仲裁问题）与提示词优化
     *       唯一可用的判据，也是将来业务签署要签的那一栏。</li>
     * </ul>
     *
     * <p>拿 {@code expect} 去优化提示词会得到一个荒谬的结果：优化器会努力让模型复现
     * 降级态下的错误答案。真值与现状必须是两栏。
     *
     * <p>两条缺省规则：
     *
     * <ul>
     *   <li>{@code locked} 用例不写 truth 时，truth 即 expect。locked 的含义本来就是
     *       「现状即期望」，再抄一遍只会让两栏日后不同步。</li>
     *   <li>{@code known-gap} 用例不写 truth 时返回 null，表示**真值待定**。诊断与优化会跳过它。
     *       这一档留给「现状明显不对，但对的是什么得业务说」——投资建议话术就属于这类，
     *       工程替它填一个真值，等于工程定了持牌业务的口径。</li>
     * </ul>
     */
    public Expect getTruth() {
        if (truth != null) {
            return truth;
        }
        return knownGap() ? null : expect;
    }

    public void setTruth(Expect truth) {
        this.truth = truth;
    }

    public boolean hasTruth() {
        return getTruth() != null;
    }

    public String getLabeledBy() {
        return labeledBy;
    }

    public void setLabeledBy(String labeledBy) {
        this.labeledBy = labeledBy == null || labeledBy.isBlank() ? LabelSource.ENGINEERING : labeledBy;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    @Override
    public String toString() {
        return id;
    }
}
