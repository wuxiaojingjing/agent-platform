package com.huawei.finance.fastpath;

/**
 * 顺序执行的快路径，留作**比对基准**。
 *
 * <p>这是把编排交给图引擎之前那份实现，逐字保留。它的用途不是生产执行
 * （生产走 {@link FastPathGraph}），而是让 {@code FastPathParityTest} 有一个可对照的答案：
 * 现有用例只能证明「跑到的分支一致」，证明不了没跑到的那些。
 *
 * <p>它与图实现共用同一份 {@link FastPathSteps}，因此两者的差别**只在顺序与分支**——
 * 这正是要比对的东西。等评测集就位、图实现在生产上跑过两个切片后删除。
 */
class SequentialFastPath {

    private final FastPathSteps steps;

    SequentialFastPath(FastPathSteps steps) {
        this.steps = steps;
    }

    FastPathResult decide(FastPathRequest request) {
        FastPathState state = new FastPathState(request);

        steps.rewrite(state);
        steps.classifyEvent(state);

        if (steps.continuationApplies(state)) {
            steps.continuation(state);
            return state.result();
        }

        steps.mergeSlotsAndKey(state);

        if (steps.cacheEnabled(state)) {
            steps.cacheLookup(state);
            if (state.decided()) {
                return state.result();
            }
        }

        steps.strongRules(state);
        if (state.decided()) {
            return state.result();
        }

        steps.recall(state);
        steps.arbitrate(state);
        return state.result();
    }
}
