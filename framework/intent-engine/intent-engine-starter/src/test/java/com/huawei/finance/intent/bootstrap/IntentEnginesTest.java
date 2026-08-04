package com.huawei.finance.intent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.intent.IntentRequest;
import com.huawei.finance.intent.IntentResult;
import org.junit.jupiter.api.Test;

class IntentEnginesTest {

    @Test
    void businessFactoryCanWrapPlatformDefault() {
        IntentEngine platformDefault = request -> null;

        IntentEngine selected = IntentEngines.select(platformDefault,
                delegate -> new DelegatingIntentEngine(delegate));

        assertThat(selected).isInstanceOf(DelegatingIntentEngine.class);
        assertThat(((DelegatingIntentEngine) selected).delegate).isSameAs(platformDefault);
    }

    @Test
    void businessFactoryCanReplacePlatformDefault() {
        IntentEngine replacement = request -> null;

        IntentEngine selected = IntentEngines.select(request -> null,
                platformDefault -> replacement);

        assertThat(selected).isSameAs(replacement);
    }

    @Test
    void nullFactoryResultFailsAtAssemblyTime() {
        assertThatNullPointerException()
                .isThrownBy(() -> IntentEngines.select(request -> null,
                        platformDefault -> null))
                .withMessage("IntentEngineFactory 返回 null");
    }

    private static final class DelegatingIntentEngine implements IntentEngine {
        private final IntentEngine delegate;

        private DelegatingIntentEngine(IntentEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public IntentResult recognize(IntentRequest request) {
            return delegate.recognize(request);
        }
    }
}
