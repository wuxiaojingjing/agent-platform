package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 按话题触发的合规提示清单（FP-36 扩展判定，§2.7.9 第一处缺口）。
 *
 * <p>现有设计里 {@code riskLevel} 挂在能力卡上，于是「买什么基金好」这类问题在整条链路上是隐形的：
 * 它不触发任何 R2 能力，护栏看不到它，风险提示也不会触发，最后大概率由知识问答直接答了。
 * 而投资建议在银行是持牌业务，答错的代价不比转错账小。
 *
 * <p>因此这条通道的触发条件与能力风险等级**并列而非从属**——不看命中了哪张卡，
 * 只看用户问的是什么话题。
 *
 * <p>S2 阶段只留位：{@code topics} 为空，行为与接入前完全一致。清单与话术口径归业务部与合规
 * （§6 阻断项 2b），工程不得自行填一份默认值充数——那会造出一种「合规已覆盖」的错觉，
 * 比空着更坏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComplianceTopics {

    private String version = "unset";
    private List<Topic> topics = List.of();

    /**
     * 匹配话题，返回命中的话题码。
     *
     * <p>关键词为空的话题**永不触发**，不是恒触发。二者都能自圆其说，选前者是因为
     * 「配了一半的话题不生效」只是漏了合规提示，而「恒触发」会让每个用户在每一句话后面
     * 都收到一段投资风险告知，那是能把面客链路直接毁掉的默认值。
     *
     * <p>匹配用子串包含，吃的是用户**原话**。这里不能用改写归一后的文本：改写表是为字面检索
     * 服务的，它会把「买什么基金好」里的词换成检索用的核心业务词，而合规要看的正是用户
     * 原本怎么问的（§5.4 的同一条理由）。
     */
    public List<String> match(String userQuery) {
        if (userQuery == null || userQuery.isBlank() || topics.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (Topic topic : topics) {
            if (topic.matches(userQuery)) {
                hits.add(topic.getNoticeCode());
            }
        }
        return List.copyOf(hits);
    }

    /** 单个受限话题。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Topic {
        private String code = "";
        /** 命中后追加到 {@code ResponsePlan.riskNoticeCodes} 的提示码。 */
        private String noticeCode = "";
        private List<String> keywords = List.of();

        boolean matches(String text) {
            if (noticeCode.isBlank() || keywords.isEmpty()) {
                return false;
            }
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code == null ? "" : code;
        }

        public String getNoticeCode() {
            return noticeCode;
        }

        public void setNoticeCode(String noticeCode) {
            this.noticeCode = noticeCode == null ? "" : noticeCode;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<Topic> getTopics() {
        return topics;
    }

    public void setTopics(List<Topic> topics) {
        this.topics = topics == null ? List.of() : List.copyOf(new ArrayList<>(topics));
    }
}
