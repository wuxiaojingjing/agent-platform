package com.huawei.finance.fastpath;

import com.huawei.finance.intent.IntentRequest;

/**
 * 门面入参与引擎内部请求之间的互转。
 *
 * <p>原先是 {@code IntentRequest} 自己的两个包内可见方法，门面与实现同包时靠包可见性挡住外人。
 * 门面搬进 intent-engine-api 之后同包没了，转换只能放在实现这一侧——**而这比原来更严**：
 * 现在依赖方的 classpath 上根本没有 {@link FastPathRequest} 这个类型，
 * 想写一个「拿 IntentRequest 换出内部请求」的调用连编译都过不去。
 *
 * <p>包内可见，不对外。
 */
final class FastPathRequests {

    private FastPathRequests() {
    }

    static FastPathRequest toFastPathRequest(IntentRequest request) {
        return new FastPathRequest(request.ctx(), request.query(), request.activeTask(),
                request.attributes(), request.contextualQuery(), request.intentContext());
    }

    /** 平行用例拿它对照两条入口（门面 vs 直调）传进去的是同一份请求。 */
    static IntentRequest toIntentRequest(FastPathRequest request) {
        return new IntentRequest(request.ctx(), request.rawQuery(), request.activeTask(),
                request.userStateValues(), request.contextualQuery(), request.intentContext());
    }
}
