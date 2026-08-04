package com.huawei.finance.fastpath.rewrite;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 中文分词与实体识别（实施架构 §2.5.6 NER 行、§2.7「中文 NER → HanLP」点名项）。
 *
 * <p>只做两件事：切词、认人名机构名。**不判断这些实体是谁**——那要靠通讯录与主数据链接，
 * 是 FP-1F 的范围。这里产出的一律是候选，不是真值。
 *
 * <p>词典加载放在构造函数里而不是首次调用时。HanLP 首次分词会加载并编译核心词典，
 * 耗时以秒计；懒加载等于把这几秒摊到某个倒霉用户的首个请求上，
 * 而快路径的预算是毫秒级的。
 */
public class ChineseAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ChineseAnalyzer.class);

    /**
     * 人名、音译人名、日本人名、机构名、机构名简称。
     *
     * <p>不含地名（ns）：银行场景里的地名多半是支行名或地址，不是收款人。
     */
    private static final Set<String> ENTITY_TAGS = Set.of("nr", "nrf", "nrj", "nt", "ntc", "nz");

    /** 对召回没有贡献的词性：标点、助词、语气词、代词、介词、连词。 */
    private static final Set<String> NOISE_PREFIXES = Set.of("w", "u", "y", "r", "p", "c", "e", "o");

    private final Segment segment;

    public ChineseAnalyzer() {
        // 开人名与机构名识别；不开地名识别，理由见 ENTITY_TAGS
        this.segment = HanLP.newSegment()
                .enableNameRecognize(true)
                .enableOrganizationRecognize(true)
                .enableNumberQuantifierRecognize(true);
        warmUp();
    }

    private void warmUp() {
        long started = System.nanoTime();
        segment.seg("给老徐转两千块");
        log.info("HanLP 词典加载完成 耗时={}ms", (System.nanoTime() - started) / 1_000_000);
    }

    public Analysis analyze(String text) {
        if (text == null || text.isBlank()) {
            return new Analysis(List.of(), List.of(), List.of());
        }

        List<Term> terms = segment.seg(text);
        Set<String> words = new LinkedHashSet<>();
        List<Entity> entities = new ArrayList<>();
        List<Entity> tokens = new ArrayList<>(terms.size());

        for (Term term : terms) {
            String tag = term.nature == null ? "" : term.nature.toString();
            tokens.add(new Entity(term.word, tag));
            if (isEntity(tag)) {
                entities.add(new Entity(term.word, tag));
            }
            if (!isNoise(tag) && term.word.length() > 1) {
                // 单字词几乎不承载检索信号，却会让 terms 查询命中大量无关卡
                words.add(term.word);
            }
        }
        return new Analysis(List.copyOf(words), List.copyOf(entities), List.copyOf(tokens));
    }

    /** 代词。用它判定「给他们转钱」里的「他们」不是收款人。 */
    public static boolean isPronoun(String tag) {
        return tag != null && tag.startsWith("r");
    }

    private static boolean isEntity(String tag) {
        return ENTITY_TAGS.contains(tag);
    }

    private static boolean isNoise(String tag) {
        return !tag.isEmpty() && NOISE_PREFIXES.contains(tag.substring(0, 1));
    }

    /**
     * @param words    去噪后的检索词，供 OpenSearch terms 查询与关键词覆盖使用
     * @param entities 人名与机构名候选，**不是**已确认的收款人
     * @param tokens   全部切分结果（含词性）。槽位抽取用它修正正则的边界错误
     */
    public record Analysis(List<String> words, List<Entity> entities, List<Entity> tokens) {

        public List<String> entityWords() {
            return entities.stream().map(Entity::word).toList();
        }

        /** 这段文本是不是纯代词。「他们」「我们」不能当收款人。 */
        public boolean isPurePronoun(String span) {
            return tokens.stream()
                    .filter(t -> t.word().equals(span))
                    .anyMatch(t -> isPronoun(t.tag()));
        }
    }

    /**
     * @param word 实体字面
     * @param tag  HanLP 词性标记，保留下来是为了让下游能区分人名与机构名
     */
    public record Entity(String word, String tag) {
    }
}
