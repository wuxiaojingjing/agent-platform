package com.huawei.finance.fastpath.rewrite;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文数字转阿拉伯数字。
 *
 * <p>为什么自己写而不交给模型：实施架构 §2.7 把「金额、卡号解析」列为**禁止用模型**，
 * 理由是要求同输入同输出。模型即便 {@code temperature=0} 也只是近似确定，
 * 而「两千」被读成 20000 与被读成 2000 的差别，在转账场景里不是精度问题。
 *
 * <p>§2.5.6 对这一项的定版本就是「正则 + 规范化（Java 内嵌）」，所以这不属于自研造轮子。
 *
 * <p>覆盖范围是口语金额：万以内的常规读法、「两」的口语形态、「万/千/百」的量级词，
 * 以及「一万五」这类省略尾数单位的说法。超出范围一律返回空，交给澄清——
 * 读不准就别读，这条比多覆盖几种说法重要。
 */
public final class ChineseNumbers {

    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0),
            Map.entry('一', 1), Map.entry('壹', 1), Map.entry('幺', 1),
            Map.entry('二', 2), Map.entry('两', 2), Map.entry('贰', 2), Map.entry('俩', 2),
            Map.entry('三', 3), Map.entry('叁', 3),
            Map.entry('四', 4), Map.entry('肆', 4),
            Map.entry('五', 5), Map.entry('伍', 5),
            Map.entry('六', 6), Map.entry('陆', 6),
            Map.entry('七', 7), Map.entry('柒', 7),
            Map.entry('八', 8), Map.entry('捌', 8),
            Map.entry('九', 9), Map.entry('玖', 9));

    private static final Map<Character, Integer> UNITS = Map.of(
            '十', 10, '拾', 10,
            '百', 100, '佰', 100,
            '千', 1000, '仟', 1000);

    private static final int WAN = 10_000;
    private static final int YI = 100_000_000;

    /** 纯中文数字串。混入阿拉伯数字的写法（「2千」）也接受，见 {@link #parse}。 */
    private static final Pattern CHINESE_NUMERAL = Pattern.compile(
            "[零〇一壹幺二两贰俩三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬亿億\\d]+");

    /** 量级词。金额里出现它，才说明这串数字是个「数目」而不是别的什么。 */
    private static final String MAGNITUDES = "十拾百佰千仟万萬亿億";

    /** 紧跟其后就能确认是钱的量词。 */
    private static final Pattern CURRENCY_UNIT = Pattern.compile("^\\s*(?:元|块|块钱|钱|人民币)");

    private ChineseNumbers() {
    }

    /**
     * 从一段文本里找出第一个中文金额并转成阿拉伯数字。
     *
     * <p>只认「带量级词」或「紧跟货币量词」的数字串。这条限制不是为了少干活，
     * 而是因为不带它就会把「查一下余额」里的「一」抽成金额 1——
     * 汉语里的「一」大多数时候不是数目。
     *
     * @return 转换结果；没找到、或找到但读不准时返回 null
     */
    public static String findFirst(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = CHINESE_NUMERAL.matcher(text);
        while (m.find()) {
            String token = m.group();
            // 纯阿拉伯数字不归这里管，正则抽取那条路已经覆盖，重复处理只会互相打架
            if (token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (!looksLikeAmount(token, text.substring(m.end()))) {
                continue;
            }
            Long value = parse(token);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static boolean looksLikeAmount(String token, String following) {
        for (int i = 0; i < token.length(); i++) {
            if (MAGNITUDES.indexOf(token.charAt(i)) >= 0) {
                return true;
            }
        }
        return CURRENCY_UNIT.matcher(following).find();
    }

    /**
     * 解析一个中文数字串。
     *
     * <p>按「亿」「万」切成三段分别求值再合并，这样「一亿两千三百万」不必特殊处理。
     *
     * @return 数值；无法确定读法时返回 null
     */
    public static Long parse(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String s = token.replace('萬', '万').replace('億', '亿');

        int yiAt = s.indexOf('亿');
        if (yiAt >= 0) {
            Long high = parse(s.substring(0, yiAt));
            Long low = s.length() > yiAt + 1 ? parse(s.substring(yiAt + 1)) : 0L;
            if (high == null || low == null) {
                return null;
            }
            return high * YI + low;
        }

        int wanAt = s.indexOf('万');
        if (wanAt >= 0) {
            Long high = parse(s.substring(0, wanAt));
            if (high == null) {
                return null;
            }
            String tail = s.substring(wanAt + 1);
            if (tail.isEmpty()) {
                return high * WAN;
            }
            Long low = parseTailAfterWan(tail);
            return low == null ? null : high * WAN + low;
        }

        return parseSection(s);
    }

    /**
     * 「一万五」的尾数。
     *
     * <p>口语里「万」之后的裸数字省略了单位：「一万五」是 15000 而不是 10005。
     * 补上一个量级——尾数只有一位就补千，两位补百，以此类推。
     * 带单位的写法（「一万五千」）不走这里，由 {@link #parseSection} 正常处理。
     */
    private static Long parseTailAfterWan(String tail) {
        boolean bareDigits = tail.chars().allMatch(c -> DIGITS.containsKey((char) c));
        Long value = parseSection(tail);
        if (value == null) {
            return null;
        }
        if (!bareDigits) {
            return value;
        }
        int magnitude = switch (tail.length()) {
            case 1 -> 1000;
            case 2 -> 100;
            case 3 -> 10;
            default -> 1;
        };
        return value * magnitude;
    }

    /**
     * 解析万以内的一段。
     *
     * <p>难点全在结尾那个没带单位的数字上：「三千五」是 3500，「三千零五」是 3005。
     * 区别只在有没有那个「零」——它的作用正是宣告「后面这位落在个位」。
     * 所以要一路记住最近用过的量级，以及自那以后有没有出现过「零」。
     */
    private static Long parseSection(String s) {
        if (s.isEmpty()) {
            return 0L;
        }
        long total = 0;
        long current = 0;
        boolean sawAny = false;
        int lastUnit = 1;
        boolean zeroSinceUnit = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (Character.isDigit(c)) {
                current = current * 10 + (c - '0');
                sawAny = true;
                continue;
            }
            Integer digit = DIGITS.get(c);
            if (digit != null) {
                if (digit == 0) {
                    zeroSinceUnit = true;
                    sawAny = true;
                    continue;
                }
                // 「一一」这种连写在金额里没有确定读法，拒绝而不是猜
                if (current != 0 && i > 0 && DIGITS.containsKey(s.charAt(i - 1))) {
                    return null;
                }
                current = digit;
                sawAny = true;
                continue;
            }
            Integer unit = UNITS.get(c);
            if (unit == null) {
                return null;
            }
            // 「十五」开头省略了「一」
            long multiplier = (current == 0 && unit == 10 && i == 0) ? 1 : current;
            if (multiplier == 0) {
                return null;
            }
            total += multiplier * unit;
            current = 0;
            lastUnit = unit;
            zeroSinceUnit = false;
            sawAny = true;
        }

        if (current == 0) {
            return sawAny ? total : null;
        }
        long trailingMagnitude = (zeroSinceUnit || lastUnit == 1) ? 1 : lastUnit / 10;
        return total + current * trailingMagnitude;
    }
}
