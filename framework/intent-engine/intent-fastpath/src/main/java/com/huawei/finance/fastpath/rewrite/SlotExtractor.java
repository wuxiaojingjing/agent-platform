package com.huawei.finance.fastpath.rewrite;

import com.huawei.finance.contracts.model.SlotNames;
import com.huawei.finance.registry.asset.ClarifyConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位抽取：正则为主，HanLP 实体与中文数字补位。
 *
 * <p>三条通路的信任级别不同，合并顺序也据此固定：
 *
 * <ol>
 *   <li><b>正则</b>——形式特征明确（卡号、日期、阿拉伯数字金额、「给X转」句式），最可信，优先。
 *   <li><b>中文数字规范化</b>——确定性算法，同输入同输出，仅在正则抽空时补金额。
 *   <li><b>HanLP 人名/机构名</b>——只在正则没抽到收款人、且**恰好识别出一个**实体时补位。
 *       零个或多个都留空走澄清。
 * </ol>
 *
 * <p>第 3 条为什么这么保守：HanLP portable 认出的是「像人名」，不是「是这个用户的联系人」。
 * 真正的收款人真值要靠通讯录链接（FP-1F），而通讯录数据源尚未确认。多个候选时挑一个，
 * 等于在转账场景里替用户猜——抽错收款人的代价远大于多问一句。
 */
public class SlotExtractor {

    private final Map<String, List<CollectionMember>> collectionWords;

    private static final String ARABIC_NUMBER =
            "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?";

    /** 明确带货币单位的数字可以独立确认为金额。 */
    private static final Pattern AMOUNT_WITH_UNIT = Pattern.compile(
            "(" + ARABIC_NUMBER + ")\\s*(?:元|块钱|块)");

    /**
     * 不带货币单位时，数字必须紧跟在资金动作后。
     *
     * <p>这条边界防止「产品B2」、「汇款报错26023」中的标识符被当成金额，
     * 同时保留「给张三转1000」、「还款1000」等常见表达。
     */
    private static final Pattern AMOUNT_AFTER_OPERATION = Pattern.compile(
            "(?:转账?|汇款?|还款|付款|支付|充值|缴费|存款?|取款?|提现|购买|买入|申购|赎回)"
                    + "(?:给[\\u4e00-\\u9fa5]{2,4})?\\s*(?:人民币|[¥￥])?\\s*("
                    + ARABIC_NUMBER + ")(?![\\d,])");

    private static final Pattern CURRENCY_MARKER = Pattern.compile("人民币|元|块钱|块|[¥￥]");

    private static final Pattern MONEY_OPERATION_CONTEXT = Pattern.compile(
            "转账|汇款|还款|付款|支付|充值|缴费|存款|取款|提现|购买|买入|申购|赎回|"
                    + "转|汇|打(?:款|钱|[零〇一壹幺二两贰俩三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬亿億\\d])");

    /** 「给老徐转」「转给张三」中的收款人。限定 2-4 个汉字，避免把整句话吞进来。 */
    private static final Pattern PAYEE_BEFORE_VERB = Pattern.compile("给([\\u4e00-\\u9fa5]{2,4})(?:转|汇|打)");
    private static final Pattern PAYEE_AFTER_VERB = Pattern.compile("(?:转给|汇给|打给)([\\u4e00-\\u9fa5]{2,4})");
    /** 「转一半给张三」：动词与「给」之间夹着金额/比例。 */
    private static final Pattern PAYEE_AFTER_AMOUNT =
            Pattern.compile("(?:转|汇|打)[^给]{0,12}给([\\u4e00-\\u9fa5]{2,4})");

    /**
     * 只有句子本身带资金划转语义时，才允许 NER 的唯一实体补成收款人。
     *
     * <p>HanLP 会把“换卡无忧”里的“无忧”等普通词偶发标成人名。过去只要整句恰好有一个
     * 实体就写入 payee，导致知识问答、产品咨询的改写上下文出现无关收款人。正则已明确定位
     * “给谁转”，这里仅约束正则覆盖不到的 NER 补位分支。
     */
    private static final Pattern PAYMENT_CONTEXT = Pattern.compile(
            "转账|汇款|转|汇|打(?:款|钱|[零〇一壹幺二两贰俩三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬亿億\\d])");

    /**
     * 16–19 位卡号，允许数字之间夹单个空格或短横线。
     *
     * <p>从短信或网银复制出来的卡号几乎都带分隔符。此前只认连写形态，「6222 0212 3456 7890」
     * 既抽不出卡号，还会被金额正则捡走开头的「6222」当成一笔转账金额。
     *
     * <p>两侧的 {@code (?<!\d)} 与 {@code (?!\d)} 不能省：没有它们，一串 22 位数字会被
     * 从中截出 19 位当卡号。位数不对的数字串宁可不认——把订单号当卡号下发比多问一句糟得多。
     */
    private static final Pattern CARD_NUMBER =
            Pattern.compile("(?<!\\d)(\\d(?:[ -]?\\d){15,18})(?!\\d)");

    private static final Pattern SEPARATORS = Pattern.compile("[ -]");

    private static final Pattern DATE = Pattern.compile("(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?)");

    private static final ChineseAnalyzer.Analysis NO_ANALYSIS =
            new ChineseAnalyzer.Analysis(List.of(), List.of(), List.of());

    /** 中文数字与量级词。用于把正则贪婪吞进收款人的金额尾巴切掉。 */
    private static final Pattern TRAILING_NUMERAL =
            Pattern.compile("[零〇一壹幺二两贰俩三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬亿億\\d]+$");

    public SlotExtractor() {
        this(null);
    }

    /**
     * 从当前资产快照编译集合词。集合词只生成结构化槽位，不改写原查询，
     * 因此不会把一个领域内成员扩散成全局同义词。
     */
    public SlotExtractor(ClarifyConfig clarify) {
        this.collectionWords = compileCollectionWords(clarify);
    }

    public Map<String, Object> extract(String normalizedQuery) {
        return extract(normalizedQuery, NO_ANALYSIS);
    }

    /**
     * @param analysis HanLP 分析结果，用于补位与边界修正；可为 {@link #NO_ANALYSIS}
     */
    public Map<String, Object> extract(String normalizedQuery, ChineseAnalyzer.Analysis analysis) {
        Map<String, Object> slots = new LinkedHashMap<>();
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return slots;
        }

        // 卡号必须先抽：不然 16 位卡号会被金额正则当成一个巨大的金额
        Matcher card = CARD_NUMBER.matcher(normalizedQuery);
        String remaining = normalizedQuery;
        if (card.find()) {
            // 存下来的是纯数字，从原文里挖掉的是带分隔符的那一段
            slots.put(SlotNames.CARD_NUMBER, SEPARATORS.matcher(card.group(1)).replaceAll(""));
            remaining = normalizedQuery.replace(card.group(1), "");
        }

        Matcher date = DATE.matcher(remaining);
        if (date.find()) {
            slots.put(SlotNames.DATE, date.group(1));
            remaining = remaining.replace(date.group(1), "");
        }

        String payee = resolvePayee(remaining, analysis);
        if (payee != null) {
            slots.put(SlotNames.PAYEE, payee);
        }

        Matcher amount = findArabicAmount(remaining);
        if (amount != null) {
            // 千分位只是显示格式，下发给领域方的必须是可解析的数
            slots.put(SlotNames.AMOUNT, amount.group(1).replace(",", ""));
        } else if (MONEY_OPERATION_CONTEXT.matcher(remaining).find()
                || CURRENCY_MARKER.matcher(remaining).find()) {
            // 中文数字走确定性转换而非模型：§2.7 把金额解析列为禁止用模型，
            // 「两千」读成 20000 与读成 2000 的差别在转账场景里不是精度问题
            String chinese = ChineseNumbers.findFirst(remaining);
            if (chinese != null) {
                slots.put(SlotNames.AMOUNT, chinese);
            }
        }

        extractCollectionWords(normalizedQuery, slots);

        return slots;
    }

    private static Matcher findArabicAmount(String text) {
        Matcher withUnit = AMOUNT_WITH_UNIT.matcher(text);
        if (withUnit.find()) {
            return withUnit;
        }
        Matcher afterOperation = AMOUNT_AFTER_OPERATION.matcher(text);
        return afterOperation.find() ? afterOperation : null;
    }

    private void extractCollectionWords(String query, Map<String, Object> slots) {
        collectionWords.forEach((slot, members) -> {
            Set<String> canonicalValues = new LinkedHashSet<>();
            for (CollectionMember member : members) {
                if (query.contains(member.surface())) {
                    canonicalValues.add(member.canonical());
                }
            }
            // 同一句出现两个不同枚举值是比较或选择，不替用户挑一个。
            if (canonicalValues.size() == 1) {
                slots.putIfAbsent(slot, canonicalValues.iterator().next());
            }
        });
    }

    private static Map<String, List<CollectionMember>> compileCollectionWords(ClarifyConfig clarify) {
        if (clarify == null || clarify.getSlots() == null || clarify.getSlots().isEmpty()) {
            return Map.of();
        }
        Map<String, List<CollectionMember>> compiled = new LinkedHashMap<>();
        clarify.getSlots().forEach((slot, definition) -> {
            if (slot == null || slot.isBlank() || definition == null) return;
            Map<String, String> mapping = definition.getValueMapping();
            List<CollectionMember> members = new ArrayList<>();
            mapping.forEach((surface, canonical) -> addMember(members, surface, canonical));
            definition.getOptions().forEach(option ->
                    addMember(members, option, mapping.getOrDefault(option, option)));
            members.sort(Comparator.comparingInt((CollectionMember member) -> member.surface().length())
                    .reversed());
            if (!members.isEmpty()) compiled.put(slot, List.copyOf(members));
        });
        return Map.copyOf(compiled);
    }

    private static void addMember(List<CollectionMember> members, String surface, String canonical) {
        if (surface == null || surface.isBlank() || canonical == null || canonical.isBlank()) return;
        CollectionMember member = new CollectionMember(surface.trim(), canonical.trim());
        if (!members.contains(member)) members.add(member);
    }

    private record CollectionMember(String surface, String canonical) {
    }

    /**
     * 收款人：正则定位，HanLP 修正边界。
     *
     * <p>正则的 {@code {2,4}} 是贪婪的，「转给老徐一千」会把金额一起吞成「老徐一千」；
     * 而「给他们转钱」会把代词当成人。这两类错误单靠调正则解决不了——
     * 收窄到 {@code {2,4}?} 又会把「王小明」截成「王小」。定位交给正则、切边交给分词，
     * 各做各擅长的那一半。
     */
    private static String resolvePayee(String text, ChineseAnalyzer.Analysis analysis) {
        String captured = regexCapture(text);
        if (captured == null) {
            if (!PAYMENT_CONTEXT.matcher(text).find()) {
                return null;
            }
            return soleEntity(analysis.entities()).orElse(null);
        }
        // HanLP 认出的人名是最可靠的边界
        for (ChineseAnalyzer.Entity entity : analysis.entities()) {
            if (captured.contains(entity.word())) {
                return entity.word();
            }
        }
        String trimmed = stripLeadingPronoun(
                TRAILING_NUMERAL.matcher(captured).replaceAll(""), analysis);
        if (trimmed.length() < 2 || analysis.isPurePronoun(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String regexCapture(String text) {
        Matcher before = PAYEE_BEFORE_VERB.matcher(text);
        if (before.find()) {
            return before.group(1);
        }
        Matcher after = PAYEE_AFTER_VERB.matcher(text);
        if (after.find()) {
            return after.group(1);
        }
        Matcher withAmount = PAYEE_AFTER_AMOUNT.matcher(text);
        return withAmount.find() ? withAmount.group(1) : null;
    }

    /** 「给我老板转两千」的正则捕获是「我老板」，前面那个「我」是代词不是姓。 */
    private static String stripLeadingPronoun(String captured, ChineseAnalyzer.Analysis analysis) {
        for (ChineseAnalyzer.Entity token : analysis.tokens()) {
            if (ChineseAnalyzer.isPronoun(token.tag())
                    && captured.startsWith(token.word())
                    && captured.length() > token.word().length()) {
                return captured.substring(token.word().length());
            }
        }
        return captured;
    }

    /**
     * 唯一的实体候选。
     *
     * <p>「恰好一个」是这里唯一接受的情形。「转给张三还是李四」认出两个人名时，
     * 挑哪一个都是猜；一个都没认出时更不能编。两种情况都留空，由澄清去问。
     */
    private static Optional<String> soleEntity(List<ChineseAnalyzer.Entity> entities) {
        if (entities == null) {
            return Optional.empty();
        }
        List<String> distinct = entities.stream()
                .map(ChineseAnalyzer.Entity::word)
                .distinct()
                .toList();
        return distinct.size() == 1 ? Optional.of(distinct.get(0)) : Optional.empty();
    }
}
