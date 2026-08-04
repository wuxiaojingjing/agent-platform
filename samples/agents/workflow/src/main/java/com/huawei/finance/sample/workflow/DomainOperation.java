package com.huawei.finance.sample.workflow;

import com.huawei.finance.stability.Spi;
import java.util.Map;

/**
 * 一次叶子调用：查一次余额、提交一次划转、取一次流水号。
 *
 * <p>这是行内二次开发唯一需要写 Java 的地方。**实现里不得出现编排**：
 * 不要在一个操作里连着调三个下游、不要自己判断「上一步失败了就走另一条路」——
 * 那是流程声明的职责。一个操作只做一件可以单独测的事，失败就抛异常，
 * 由声明里的 {@code onError} 决定这次失败该归成哪一类。
 *
 * <p>也不要在实现里重试。重试资格由中控按 {@code failureClass} 判定：
 * 领域侧自行重试会让同一笔业务在中控看来只发生过一次，对账时对不上。
 */
@Spi
public interface DomainOperation {

    /** 操作名，与流程声明里的 {@code operation} 对应。全局唯一。 */
    String name();

    /**
     * 执行这一步。
     *
     * @param ctx 本次任务的入参与在此之前各步的产出
     * @return 本步产出，会以步骤 id 为键挂进流程状态供后续步骤与结果映射引用；
     *         无产出返回空 Map，不要返回 null
     */
    Map<String, Object> execute(OperationContext ctx);
}
