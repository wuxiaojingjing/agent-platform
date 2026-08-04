package com.huawei.finance.fastpath.rewrite;

import java.util.List;

/**
 * 改写结果。
 *
 * <p>各字段有各自的去处，不能互相替代：
 *
 * <ul>
 *   <li>{@code original} 面客渲染只用它。把改写后的句子回显给用户，
 *       用户会看到自己没说过的话（v0.7 §2.5.4：改写仅服务检索）。
 *   <li>{@code normalized} 规则判定与出口缓存键用它。缓存键取它而非原文是有意的：
 *       几种口语说法归一后落到同一个键上，命中率高得多。
 *   <li>{@code searchText} 去掉停用词后交给 BM25 全文匹配。
 *   <li>{@code semanticText} <b>模型侧专用</b>——语义召回与仲裁 prompt 用它，
 *       它没有经过纠错与同义替换。见下方说明。
 *   <li>{@code terms} HanLP 切出的词，交给 keywords 字段的 terms 精确匹配。
 *       中文没有空白分隔，拿整句去 terms 匹配一个 keyword 字段几乎不可能命中。
 *   <li>{@code analysis} 分词与实体识别结果，供槽位抽取补位与修正边界。
 *       其中的人名机构名**是候选不是真值**：未经通讯录链接不得当作已确认的收款人。
 * </ul>
 *
 * <p><b>为什么模型侧要单独一份文本</b>（FP-11）。改写表是为**字面匹配**服务的：BM25 与规则
 * 通道按词面比对，「帐单」不先纠成「账单」就一条也命中不了。模型侧的情况正相反——
 * Qwen3-Embedding 本来就吸收同义与错别字，改写给不了它任何好处，却会拿走信息。
 *
 * <p>这不是理论推演。{@code cap.account.balance.query} 的 utterances 里逐字写着
 * 「卡里还有多少钱」，而同义表恰好把这句话整体替换成「余额」。也就是说，用户说出与文档
 * <b>完全相同</b>的那句话时，我们先把它改写掉，再拿改写结果去和原句做向量匹配——
 * 亲手抹掉了能拿到的最高分。同理，仲裁 prompt 里写着「用户输入：{@code {{query}}}」，
 * 塞进去的却是一句用户没说过的话。
 *
 * <p>所以分工是：**字面通道吃改写后的文本，模型通道吃原话**。
 */
public record RewriteResult(String original, String normalized, String searchText,
                            String semanticText, List<String> terms,
                            ChineseAnalyzer.Analysis analysis) {

    public List<ChineseAnalyzer.Entity> entities() {
        return analysis.entities();
    }
}
