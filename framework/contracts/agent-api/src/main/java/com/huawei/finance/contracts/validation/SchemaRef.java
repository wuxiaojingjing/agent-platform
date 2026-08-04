package com.huawei.finance.contracts.validation;

/** 契约 Schema 索引。资源路径集中在此，避免各模块散落字符串常量。 */
public enum SchemaRef {

    RECALL_RESULT("schema/recall-result.schema.json"),
    ROUTE_DECISION("schema/route-decision.schema.json"),
    TASK_SHAPE_MODEL_OUTPUT("schema/task-shape-model-output.schema.json"),
    CONTEXTUAL_QUERY_OUTPUT("schema/contextual-query-output.schema.json"),
    SUBTASK_CONTEXT_ENVELOPE("schema/subtask-context-envelope.schema.json"),
    CONTEXT_DELTA("schema/context-delta.schema.json"),
    CONTINUATION_MODEL_OUTPUT("schema/continuation-model-output.schema.json"),
    LOOP_ACTION_PROPOSAL("schema/loop-action-proposal.schema.json"),
    UNIFIED_TASK("schema/unified-task.schema.json"),
    TASK_RESULT("schema/task-result.schema.json"),
    RESPONSE_PLAN("schema/response-plan.schema.json"),
    XIAOI_EXTERNAL_EVIDENCE("schema/xiaoi-external-evidence.schema.json"),
    CAPABILITY_CARD("schema/capability-card.schema.json");

    private final String resourcePath;

    SchemaRef(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
