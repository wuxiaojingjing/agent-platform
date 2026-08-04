package com.huawei.finance.sample.workflow;

import com.huawei.finance.stability.Api;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张能力卡对应的办理流程声明。
 *
 * <p>这是本模块存在的理由：办理流程（先查什么、再提交什么、哪一步失败算可重试）
 * 以声明存在于资产目录，由 OpenJiuwen 图引擎执行；行内只写叶子操作
 * （{@link DomainOperation}，一次调用、无编排），不再手写「查完再提交、提交失败要判类型」
 * 这类编排代码——那部分每个能力都要重写一遍，而且每次都可能写出不一样的失败语义。
 *
 * @param capabilityId 承接的能力，须与能力卡一致
 * @param version      流程版本，进 Trace 用于对齐「这单是按哪版流程办的」
 * @param description  给评审看的一句话
 * @param steps        顺序即执行顺序
 * @param result       {@code TaskResult.resultPayload} 的取值声明：键为输出字段，值为取值路径
 */
@Api
public record FlowSpec(
        String capabilityId,
        String version,
        String description,
        List<Step> steps,
        Map<String, String> result) {

    public FlowSpec {
        steps = steps == null ? List.of() : List.copyOf(steps);
        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
    }

    /**
     * 一步办理。
     *
     * <p>{@code when} / {@code unless} 只支持「某个路径是否为真」这一种判断，刻意不做表达式语言：
     * 分支条件一旦能写表达式，业务判断就会从领域方的代码悄悄搬进 YAML，
     * 而 YAML 里的判断没有单元测试、没有类型、审计时也说不清是谁定的。
     * 需要更复杂的条件，就让某个操作返回一个布尔字段，再用它做分支——
     * 那样条件本身是可测的。
     *
     * @param id        步骤标识，同时是它的输出在流程状态里的键名
     * @param operation 叶子操作名，须有同名 {@link DomainOperation} 实现
     * @param when      仅当该路径取值为真时执行
     * @param unless    仅当该路径取值为假时执行
     * @param onError   该步抛错时的失败归类，缺省 FATAL
     */
    public record Step(String id, String operation, String when, String unless, OnError onError) {

        public Step {
            onError = onError == null ? OnError.FATAL : onError;
        }
    }

    /**
     * 步骤失败的归类口径。
     *
     * <p>缺省 FATAL 是刻意的：把「没写清楚」默认成可重试，等于让一次没人想过的失败
     * 自动获得重放资格。重试资格必须是有人显式签过字的。
     *
     * <p>注意这里只是**归类**，不是重试动作。要不要重试由中控按 {@code failureClass} 决定，
     * 领域侧无权自行重试（{@code DomainAgent} 契约）。
     */
    public enum OnError {
        /** 下游超时、限流、临时不可用这类可以原样重放的失败。 */
        RETRYABLE,
        /** 参数不合、业务规则拒绝这类重放也不会变的失败。 */
        FATAL,
        /** 缺信息，要回去问用户。中控据此迁 CLARIFY_PENDING。 */
        NEED_USER
    }
}
