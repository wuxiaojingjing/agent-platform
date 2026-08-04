package com.huawei.finance.fastpath.rule;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aviator 表达式求值器（实施架构 §2.7：规则表达式复用 Aviator，不自研）。
 *
 * <p>表达式在首次使用时编译并缓存。快路径的预算是毫秒级，每次请求重新解析表达式
 * 会把规则通道变成瓶颈。
 *
 * <p>求值失败一律返回 false 而不是抛出。规则写错时应当表现为「这条规则没生效」，
 * 而不是「整条快路径 500」——后者会让一条拼错的规则拖垮全部流量。
 */
public class ExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEvaluator.class);

    private final AviatorEvaluatorInstance engine = AviatorEvaluator.newInstance();
    private final Map<String, Expression> compiled = new ConcurrentHashMap<>();

    public boolean evaluateBoolean(String ruleId, String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try {
            Expression expr = compiled.computeIfAbsent(expression, e -> engine.compile(e, true));
            Object result = expr.execute(context);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("规则表达式求值失败，视为未命中 ruleId={} cause={}", ruleId, e.toString());
            return false;
        }
    }

    /** 启动期预编译，把语法错误暴露在发布时而不是第一个用户请求时。 */
    public void precompile(String ruleId, String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            compiled.computeIfAbsent(expression, e -> engine.compile(e, true));
        } catch (Exception e) {
            throw new IllegalStateException("规则表达式无法编译 ruleId=" + ruleId + " expr=" + expression, e);
        }
    }
}
