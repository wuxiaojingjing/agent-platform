package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 标准问答库（FP-1I 小 i 共存）。
 *
 * <p>一条条目 = 一个标准问 + 若干句法模版 + 一份标准答案（+ 可选的动作入口）。
 * 已审批条目命中即直接念答案；来源损坏或答案未审批的条目只输出安全引导和相关选项。
 * 两类条目都不建任务，只有用户随后明确选择办理时才重新进入正常入口路由。
 *
 * <p><b>答案是人写的静态文本，不是模型生成的</b>。可以带 {@code ${变量}}，
 * 变量只从抽槽结果里取，取不到就不该命中——见 {@link Entry#requiredSlots}。
 *
 * <p><b>整条库默认为空</b>。与 {@link ComplianceTopics} 同一个理由：填几条样例进去，
 * 会让人以为知识问答已经覆盖了，而真正的标准问答口径归业务与客服，不归工程。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StandardQaBank {

    private String version = "unset";
    private List<Entry> items = List.of();

    /**
     * 找出命中的那一条。
     *
     * <p><b>命中多条时按顺序取第一条，并且这不是「随便挑一个」</b>：
     * 资产校验（{@code AssetLint}）会拦住模版互相覆盖的条目，所以正常资产下这里不会有歧义；
     * 万一漏过了，取第一条至少是确定性的——同一句话在所有实例上得到同一个答案。
     *
     * @param userQuery 用户**原话**。不吃改写结果：改写表是为字面检索服务的，
     *                  它会把口语换成核心业务词，而标准问的模版本来就是照着口语写的
     */
    public Optional<Entry> match(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return Optional.empty();
        }
        for (Entry entry : items) {
            if (entry.matches(userQuery)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** 一条标准问答。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {

        public enum Status {
            APPROVED,
            BLOCKED_SOURCE_REVIEW,
            BLOCKED_ANSWER_APPROVAL
        }

        private String id = "";
        /** 标准问：给人看的，也是运营台上这条的名字。不参与匹配。 */
        private String question = "";
        /** 句法模版。写法见 {@link SyntacticTemplate}。 */
        private List<String> patterns = List.of();
        /** 标准答案。可含 {@code ${槽位名}}。 */
        private String answer = "";
        /** 来源/答案不可用时的安全引导，不得包含未经审批的业务结论。 */
        private String guidance = "";
        /** 安全引导后的自然语言选项；点击后作为新一轮输入重新路由。 */
        private List<String> options = List.of();
        /** 默认只允许念审批答案；阻断状态绝不能把原始占位或损坏内容当答案。 */
        private Status status = Status.APPROVED;
        /** 可选来源引用，随 evidenceRefs 进入观测。 */
        private String sourceRef = "";
        /**
         * 答案中展示的菜单引用。保存稳定 menuId 而不是页面文字，避免“存款”等同名入口跳错。
         * 点击后仍作为新一轮输入进入正常入口路由，不在知识出口直接执行导航。
         */
        private List<String> menuOptions = List.of();
        /** 答案里用到的槽位。缺一个就不命中——宁可走正常链路，也不要念出一句带空洞的答案。 */
        private List<String> requiredSlots = List.of();
        /** 可选的动作入口：答完之后给一个「去办」的按钮，指向某张能力卡。 */
        private String actionCapabilityId = "";
        private String actionLabel = "";

        private transient List<SyntacticTemplate> compiled;

        boolean matches(String text) {
            if (status == Status.APPROVED ? answer.isBlank() : guidance.isBlank()) {
                return false;
            }
            for (SyntacticTemplate template : compiled()) {
                if (template.matches(text)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 编译后的模版。
         *
         * <p>编译不通过的条目**整条失效**而不是抛异常打断加载：一条写歪的标准问
         * 不该让整个应用起不来。真正拦它的地方在 CI 的资产校验，那里是红的。
         */
        public synchronized List<SyntacticTemplate> compiled() {
            if (compiled == null) {
                List<SyntacticTemplate> built = new ArrayList<>();
                for (String pattern : patterns) {
                    try {
                        built.add(SyntacticTemplate.compile(pattern));
                    } catch (IllegalArgumentException e) {
                        // 忽略这一条模版，其余模版照常生效
                    }
                }
                compiled = List.copyOf(built);
            }
            return compiled;
        }

        public boolean hasAction() {
            return !actionCapabilityId.isBlank();
        }

        public boolean isBlocked() {
            return status != Status.APPROVED;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question == null ? "" : question;
        }

        public List<String> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<String> patterns) {
            this.patterns = patterns == null ? List.of() : List.copyOf(patterns);
            this.compiled = null;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer == null ? "" : answer;
        }

        public String getGuidance() {
            return guidance;
        }

        public void setGuidance(String guidance) {
            this.guidance = guidance == null ? "" : guidance;
        }

        public List<String> getOptions() {
            return options;
        }

        public void setOptions(List<String> options) {
            this.options = options == null ? List.of() : List.copyOf(options);
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status == null ? Status.APPROVED : status;
        }

        public String getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(String sourceRef) {
            this.sourceRef = sourceRef == null ? "" : sourceRef;
        }

        public List<String> getMenuOptions() {
            return menuOptions;
        }

        public void setMenuOptions(List<String> menuOptions) {
            this.menuOptions = menuOptions == null ? List.of() : List.copyOf(menuOptions);
        }

        public List<String> getRequiredSlots() {
            return requiredSlots;
        }

        public void setRequiredSlots(List<String> requiredSlots) {
            this.requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        }

        public String getActionCapabilityId() {
            return actionCapabilityId;
        }

        public void setActionCapabilityId(String actionCapabilityId) {
            this.actionCapabilityId = actionCapabilityId == null ? "" : actionCapabilityId;
        }

        public String getActionLabel() {
            return actionLabel;
        }

        public void setActionLabel(String actionLabel) {
            this.actionLabel = actionLabel == null ? "" : actionLabel;
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<Entry> getItems() {
        return items;
    }

    public void setItems(List<Entry> items) {
        this.items = items == null ? List.of() : List.copyOf(new ArrayList<>(items));
    }
}
