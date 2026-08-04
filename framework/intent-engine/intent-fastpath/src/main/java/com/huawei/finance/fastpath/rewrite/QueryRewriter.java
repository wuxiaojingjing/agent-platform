package com.huawei.finance.fastpath.rewrite;

import com.huawei.finance.registry.asset.SynonymTable;
import java.util.Map;

/**
 * 改写归一（v0.7 §2.5.4）。
 *
 * <p>顺序是：先纠错别字，再做同义替换，最后去停用词。反过来做会出问题——
 * 「帐单」若不先纠正为「账单」，同义表里以「账单」为键的条目一条都命中不了。
 *
 * <p>改写产出的所有文本都<b>只服务字面匹配通道</b>。模型侧（语义召回、仲裁 prompt）走
 * {@link RewriteResult#semanticText()}，那一份不经纠错也不经同义替换——理由见
 * {@code RewriteResult} 的类注释，一句话是：改写会把用户与文档逐字相同的那句话改掉。
 */
public class QueryRewriter {

    private final SynonymTable table;
    private final ChineseAnalyzer analyzer;

    public QueryRewriter(SynonymTable table, ChineseAnalyzer analyzer) {
        this.table = table;
        this.analyzer = analyzer;
    }

    public RewriteResult rewrite(String rawQuery) {
        return rewriteContextual(rawQuery, rawQuery);
    }

    /** Contextual semantic rewrite first, lexical normalization second, while preserving user wording. */
    public RewriteResult rewriteContextual(String rawQuery, String standaloneQuery) {
        String original = rawQuery == null ? "" : rawQuery.trim();
        String contextual = standaloneQuery == null || standaloneQuery.isBlank()
                ? original : standaloneQuery.trim();

        String normalized = contextual;
        for (Map.Entry<String, String> e : table.getCorrections().entrySet()) {
            normalized = normalized.replace(e.getKey(), e.getValue());
        }
        // 已按 key 长度降序，长表述优先命中，避免「卡里还有多少钱」被「还有多少钱」抢先替换
        for (Map.Entry<String, String> e : table.orderedReplacements()) {
            normalized = normalized.replace(e.getKey(), e.getValue());
        }

        String searchText = normalized;
        for (String stopword : table.getStopwords()) {
            searchText = searchText.replace(stopword, "");
        }

        // 实体在 normalized 上认而不在 searchText 上认：去停用词会把「给」「的」这类
        // 人名的左右边界线索抹掉，HanLP 少了边界就容易把人名和邻词粘成一个词
        ChineseAnalyzer.Analysis analysis = analyzer.analyze(normalized);

        return new RewriteResult(original, normalized, searchText.trim(), contextual,
                analyzer.analyze(searchText.trim()).words(), analysis);
    }
}
