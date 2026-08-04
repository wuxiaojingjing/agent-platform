package com.huawei.finance.testkit;

public final class AgentYamlFixture {

    private AgentYamlFixture() {
    }

    public static String scaffold(String agentId, String domain) {
        return """
                agent:
                  id: %s
                  domains: [%s]
                implementation:
                  mode: scaffold
                """.formatted(agentId, domain);
    }

    public static String extension(String agentId, String domain, String artifact) {
        return """
                agent:
                  id: %s
                  domains: [%s]
                implementation:
                  mode: extension
                  artifact: %s
                """.formatted(agentId, domain, artifact);
    }
}
