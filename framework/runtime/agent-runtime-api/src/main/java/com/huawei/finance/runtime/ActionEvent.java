package com.huawei.finance.runtime;

import com.huawei.finance.stability.Api;

/** Structured counterpart of a natural-language continuation input. */
@Api
public record ActionEvent(String event, String ref, long version) {
    public ActionEvent {
        event = event == null ? "" : event.trim();
        ref = ref == null ? "" : ref.trim();
    }
}
