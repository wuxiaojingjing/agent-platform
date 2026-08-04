package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.Yaml;

/** Executable ownership rules for the developer-facing repository layout. */
class AgentPlatformLayoutTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    @Test
    void everyAgentFollowsTheDirectoryContract() throws IOException {
        Path agents = ReactorLayout.repoRoot().resolve("agents");
        Set<String> ids = new HashSet<>();
        int applications = 0;
        int extensions = 0;
        int scaffolds = 0;

        try (Stream<Path> children = Files.list(agents)) {
            List<Path> agentDirs = children.filter(Files::isDirectory).sorted().toList();
            assertThat(agentDirs).isNotEmpty();
            for (Path agentDir : agentDirs) {
                String directory = agentDir.getFileName().toString();
                assertThat(directory).matches(KEBAB_CASE);
                assertThat(agentDir.resolve("agent.yaml")).isRegularFile();
                assertThat(agentDir.resolve("README.md")).isRegularFile();
                assertThat(agentDir.resolve("assets")).isDirectory();
                assertThat(agentDir.resolve("eval/README.md")).isRegularFile();
                assertThat(agentDir.resolve("deploy/README.md")).isRegularFile();

                Map<?, ?> root = new Yaml().load(Files.readString(agentDir.resolve("agent.yaml")));
                assertThat(root.containsKey("agent")).isTrue();
                Map<?, ?> identity = (Map<?, ?>) root.get("agent");
                String id = String.valueOf(identity.get("id"));
                assertThat(id).startsWith("agent.");
                assertThat(ids.add(id)).as("duplicate agentId %s", id).isTrue();
                Map<?, ?> implementation = (Map<?, ?>) root.get("implementation");
                assertThat(implementation).as("%s implementation", directory).isNotNull();
                String mode = String.valueOf(implementation.get("mode"));
                assertThat(mode).isIn("application", "extension", "scaffold");
                if ("application".equals(mode)) {
                    applications++;
                } else if ("extension".equals(mode)) {
                    extensions++;
                    assertThat(agentDir.resolve(Path.of("backend", "pom.xml"))).isRegularFile();
                    assertThat(String.valueOf(implementation.get("artifact")))
                            .startsWith("com.huawei.finance:");
                } else {
                    scaffolds++;
                }
            }
        }

        assertThat(ids).hasSize(27);
        assertThat(applications).isEqualTo(1);
        assertThat(extensions).isEqualTo(11);
        assertThat(scaffolds).isEqualTo(15);

        Map<?, ?> mobile = new Yaml().load(Files.readString(
                agents.resolve("mobile-banking-assistant").resolve("agent.yaml")));
        assertThat(((Map<?, ?>) mobile.get("agent")).get("id"))
                .isEqualTo("agent.mobile-banking-assistant");
    }

    @Test
    void frameworkDoesNotCompileAgainstRedisson() throws IOException {
        Path framework = ReactorLayout.repoRoot().resolve("framework");
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(framework)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> path.toString().contains("/src/main/")
                            || (path.getFileName().toString().equals("pom.xml")
                            && !path.toString().contains("/framework/bom/")))
                    .forEach(path -> {
                        try {
                            if (Files.readString(path).contains("org.redisson")) {
                                offenders.add(framework.relativize(path));
                            }
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void businessAssetsAreOwnedByAgents() {
        Path root = ReactorLayout.repoRoot();
        assertThat(root.resolve("assets")).doesNotExist();
        assertThat(root.resolve("console")).doesNotExist();
        assertThat(root.resolve("eval")).doesNotExist();
        assertThat(root.resolve("deploy")).doesNotExist();
        assertThat(root.resolve("framework/agent-common")).doesNotExist();
        assertThat(root.resolve("framework/agent-contracts")).doesNotExist();
        assertThat(root.resolve("infrastructure/a2a/a2a-contracts")).doesNotExist();
        assertThat(root.resolve("infrastructure/samples")).doesNotExist();
    }

    @Test
    void localComposeMountsArtifactDirectoriesInsteadOfLiveJarFiles() throws IOException {
        Path root = ReactorLayout.repoRoot();
        String compose = Files.readString(root.resolve("dev/local/docker-compose.yml"));

        assertThat(compose)
                .doesNotContain("./.dist/agent-host-app.jar:/opt/app/app.jar")
                .doesNotContain("./.dist/mobile-banking-assistant.jar:/opt/app/app.jar")
                .doesNotContain("./.dist/a2a-gateway-app.jar:/opt/app/app.jar")
                .doesNotContain("/opt/extension/extension.jar:ro")
                .contains("./.dist:/opt/dist:ro")
                .contains("./.dist/extensions:/opt/extension:ro")
                .contains("/opt/dist/agent-host-app.jar")
                .contains("/opt/dist/mobile-banking-assistant.jar")
                .contains("/opt/dist/a2a-gateway-app.jar")
                .contains("HUAWEI_FINANCE_AGENT_LOOP_ENABLED: \"true\"")
                .contains("HUAWEI_FINANCE_MOBILE_BANKING_CONSOLE_WRITE_ENABLED: \"true\"");
    }

    @Test
    void platformAndProductionAgentsDoNotDependOnAgentImplementations() throws Exception {
        Path root = ReactorLayout.repoRoot();
        Set<String> agentArtifacts = backendArtifactIds(root.resolve("agents"));
        Set<String> sampleArtifacts = backendArtifactIds(root.resolve("samples/agents"));
        Set<String> forbidden = new HashSet<>(agentArtifacts);
        forbidden.addAll(sampleArtifacts);

        for (Path pom : pomsUnder(root.resolve("framework"), root.resolve("infrastructure"))) {
            assertThat(dependencies(pom)).noneMatch(dep -> forbidden.contains(dep.artifactId));
        }
        for (Path pom : pomsUnder(root.resolve("agents"))) {
            String self = artifactId(pom);
            assertThat(dependencies(pom)).noneMatch(dep -> !"test".equals(dep.scope)
                    && forbidden.contains(dep.artifactId) && !dep.artifactId.equals(self));
        }

        Path bom = root.resolve("framework/bom/agent-bom/pom.xml");
        assertThat(dependencies(bom)).noneMatch(dep -> forbidden.contains(dep.artifactId));
        assertThat(dependencies(root.resolve("framework/starters/agent-starter/pom.xml")))
                .noneMatch(dep -> dep.artifactId.equals("a2a-inprocess-testkit"));
    }

    @Test
    void legacyBrandDoesNotAppearInCurrentSources() throws IOException {
        Path root = ReactorLayout.repoRoot();
        List<String> banned = List.of(
                "gong" + "xiaozhi", "Gong" + "xiaozhi", "工" + "小智",
                "com." + "g" + "xz", "g" + "xz", "G" + "xz", "G" + "XZ");
        List<Path> offenders = new ArrayList<>();
        for (String top : List.of("framework", "infrastructure", "agents", "samples", "tools", "scripts", "dev")) {
            Path dir = root.resolve(top);
            if (!Files.exists(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !path.toString().contains("/target/"))
                        .filter(path -> !path.toString().contains("/node_modules/"))
                        .filter(path -> !path.toString().contains("/dist/"))
                        .filter(path -> !path.toString().contains("/logs/"))
                        .filter(path -> !path.getFileName().toString().equals("application-local.yml"))
                        .filter(path -> !path.getFileName().toString().equals("env.local.sh"))
                        .forEach(path -> {
                            try {
                                String text = Files.readString(path, StandardCharsets.UTF_8);
                                if (banned.stream().anyMatch(text::contains)) {
                                    offenders.add(root.relativize(path));
                                }
                            } catch (IOException ignored) {
                                // Binary or generated files are outside the source naming contract.
                            }
                        });
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void everyExecutableCapabilityResolvesToARealAgent() throws IOException {
        Path agentsRoot = ReactorLayout.repoRoot().resolve("agents");
        Set<String> agentIds = new LinkedHashSet<>();
        Map<String, String> agentByDomain = new LinkedHashMap<>();

        try (Stream<Path> children = Files.list(agentsRoot)) {
            for (Path agentDir : children.filter(Files::isDirectory).sorted().toList()) {
                Map<?, ?> root = new Yaml().load(Files.readString(agentDir.resolve("agent.yaml")));
                Map<?, ?> identity = (Map<?, ?>) root.get("agent");
                String agentId = String.valueOf(identity.get("id"));
                agentIds.add(agentId);
                for (Object domain : (List<?>) identity.get("domains")) {
                    agentByDomain.putIfAbsent(String.valueOf(domain), agentId);
                }
            }
        }

        List<String> unresolved = new ArrayList<>();
        List<String> excessiveCandidates = new ArrayList<>();
        List<String> virtualParents = new ArrayList<>();
        try (Stream<Path> files = Files.walk(agentsRoot)) {
            for (Path yaml : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("/assets/capabilities/"))
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .toList()) {
                Object loaded = new Yaml().load(Files.readString(yaml));
                if (!(loaded instanceof List<?> rows)) {
                    continue;
                }
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> card) || "AGENT".equals(String.valueOf(card.get("type")))) {
                        continue;
                    }
                    String capabilityId = String.valueOf(card.get("capabilityId"));
                    if (capabilityId.isBlank() || "null".equals(capabilityId)) {
                        continue;
                    }
                    LinkedHashSet<String> candidates = new LinkedHashSet<>();
                    if (agentIds.contains(capabilityId)) {
                        candidates.add(capabilityId);
                    }
                    Object rawParent = card.get("parentCapabilityId");
                    if (rawParent != null) {
                        String parent = String.valueOf(rawParent);
                        if (parent.startsWith("agent.") && !agentIds.contains(parent)) {
                            virtualParents.add(capabilityId + " -> " + parent);
                        }
                        if (agentIds.contains(parent)) {
                            candidates.add(parent);
                        }
                    }
                    Object rawDomains = card.get("domains");
                    if (rawDomains instanceof List<?> domains) {
                        for (Object domain : domains) {
                            String target = agentByDomain.get(String.valueOf(domain));
                            if (target != null) {
                                candidates.add(target);
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        unresolved.add(capabilityId);
                    } else if (candidates.size() > 2) {
                        excessiveCandidates.add(capabilityId + " -> " + candidates);
                    }
                }
            }
        }

        assertThat(virtualParents).as("virtual Agent parents").isEmpty();
        assertThat(unresolved).as("capabilities without a preferred Agent").isEmpty();
        assertThat(excessiveCandidates).as("capabilities with more than one reroute").isEmpty();
    }

    private static List<Path> pomsUnder(Path... roots) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                        .filter(path -> !path.toString().contains("/target/"))
                        .forEach(result::add);
            }
        }
        return result;
    }

    private static Set<String> backendArtifactIds(Path root) throws Exception {
        Set<String> result = new HashSet<>();
        for (Path pom : pomsUnder(root)) {
            result.add(artifactId(pom));
        }
        return result;
    }

    private static String artifactId(Path pom) throws Exception {
        Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
                .getDocumentElement();
        return directChild(project, "artifactId");
    }

    private static List<Dependency> dependencies(Path pom) throws Exception {
        Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
                .getDocumentElement();
        List<Dependency> result = new ArrayList<>();
        var nodes = project.getElementsByTagName("dependency");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element dependency = (Element) nodes.item(i);
            result.add(new Dependency(directChild(dependency, "artifactId"),
                    directChild(dependency, "scope")));
        }
        return result;
    }

    private static String directChild(Element parent, String name) {
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            if (parent.getChildNodes().item(i) instanceof Element child
                    && child.getTagName().equals(name)) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    private record Dependency(String artifactId, String scope) {
    }
}
