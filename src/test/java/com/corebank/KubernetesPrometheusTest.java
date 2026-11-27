package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The cluster's Prometheus runs the compose stack's config, and that config fits the cluster.
 *
 * <p>It was proven once on a live kind cluster (targets up, all rules loaded, one firing). This is
 * what keeps it true afterwards without one: a Service renamed, a scrape target added for compose
 * only, an image bumped in one place, or a deploy path that stopped loading the real files would
 * each leave the cluster's alerting quietly broken.
 */
class KubernetesPrometheusTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(String path) throws IOException {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml().loadAll(read(path))) {
            if (document != null) {
                documents.add((Map<String, Object>) document);
            }
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @Test
    @DisplayName("every target Prometheus scrapes is a Service in the cluster, on that port")
    void everyScrapeTargetIsAService() throws IOException {
        List<String> services = new ArrayList<>();
        for (Path manifest : Files.newDirectoryStream(Path.of("k8s"), "*.yaml")) {
            for (Map<String, Object> document : documents(manifest.toString())) {
                if ("Service".equals(document.get("kind"))) {
                    String name = (String) map(document.get("metadata")).get("name");
                    for (Object port : list(map(document.get("spec")).get("ports"))) {
                        services.add(name + ":" + map(port).get("port"));
                    }
                }
            }
        }

        List<String> targets = new ArrayList<>();
        Map<String, Object> config = new Yaml().load(read("observability/prometheus.yml"));
        for (Object job : list(config.get("scrape_configs"))) {
            for (Object group : list(map(job).get("static_configs"))) {
                for (Object target : list(map(group).get("targets"))) {
                    targets.add((String) target);
                }
            }
        }

        assertThat(targets).isNotEmpty();
        assertThat(services).containsAll(targets);
    }

    @Test
    @DisplayName("the cluster runs the Prometheus version compose runs, with the config mounted where it reads it")
    void theDeploymentMatchesCompose() throws IOException {
        Map<String, Object> deployment = documents("k8s/prometheus.yaml").stream()
                .filter(document -> "Deployment".equals(document.get("kind"))).findFirst().orElseThrow();
        Map<String, Object> pod = map(map(map(deployment.get("spec")).get("template")).get("spec"));
        Map<String, Object> container = map(list(pod.get("containers")).getFirst());

        Map<String, Object> compose = new Yaml().load(read("compose.yaml"));
        String composeImage = (String) map(map(compose.get("services")).get("prometheus")).get("image");
        assertThat(container.get("image")).isEqualTo(composeImage);

        assertThat(list(container.get("args"))).contains("--config.file=/etc/prometheus/prometheus.yml");
        assertThat(list(container.get("volumeMounts"))).anySatisfy(mount -> {
            assertThat(map(mount).get("name")).isEqualTo("config");
            assertThat(map(mount).get("mountPath")).isEqualTo("/etc/prometheus");
        });
        assertThat(list(pod.get("volumes"))).anySatisfy(volume -> assertThat(
                map(map(volume).get("configMap")).get("name")).isEqualTo("prometheus-config"));
        assertThat(list(documents("k8s/kustomization.yaml").getFirst().get("resources"))).contains("prometheus.yaml");
    }

    @Test
    @DisplayName("both ways of deploying load the config from observability/, the files compose mounts and CI tests")
    void bothDeployPathsLoadTheRealFiles() throws IOException {
        for (String deployPath : List.of("k8s/deploy.sh", "terraform/main.tf")) {
            String script = read(deployPath).replace("\\\n", " ");
            assertThat(script).as(deployPath)
                    .contains("create configmap prometheus-config")
                    .contains("--from-file=prometheus.yml=observability/prometheus.yml")
                    .contains("--from-file=alerts.yml=observability/alerts.yml");
        }
    }
}
