package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/** A versioned user action. The server still applies the same continuation policy gate. */
@Api
public record ResponseAction(String event, String label, String ref, long version, Style style) {
    public enum Style { PRIMARY, SECONDARY, DANGER }

    public ResponseAction {
        style = style == null ? Style.SECONDARY : style;
    }
}
