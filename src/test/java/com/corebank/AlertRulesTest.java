package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.corebank.account.service.HoldExpiryRunner;
import com.corebank.account.service.HoldService;
import com.corebank.account.service.InterestRunner;
import com.corebank.account.service.InterestService;
import com.corebank.ledger.LedgerCheckRunner;
import com.corebank.ledger.LedgerCheckService;
import com.corebank.outbox.OutboxBacklogMonitor;
import com.corebank.outbox.repository.OutboxEventRepository;
import com.corebank.schedule.ScheduledTransferRunner;
import com.corebank.schedule.service.ScheduledTransferService;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

/**
 * The alert rules and the application agree on what the metrics are called.
 *
 * <p>promtool proves each rule fires on the series it is given, but it takes the metric's name on
 * trust. Micrometer adds units and suffixes on the way to Prometheus ({@code _seconds},
 * {@code _total}), so a rule can name a metric the application has never exported, and that alert
 * never fires and never says so. This builds every component that registers a metric the rules
 * use, in a real Prometheus registry, and reads the names off the scrape.
 */
class AlertRulesTest {

    private static final Path RULES = Path.of("observability", "alerts.yml");
    private static final Pattern METRIC = Pattern.compile("\\bcorebank_[a-z_]+");

    private static List<Map<String, Object>> rules;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readRules() throws IOException {
        Map<String, Object> file = new Yaml().load(Files.readString(RULES));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) file.get("groups");
        rules = groups.stream().flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream()).toList();
    }

    private static String scrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new LedgerCheckService(mock(JdbcTemplate.class), Clock.systemUTC(), registry);
        new LedgerCheckRunner(mock(LedgerCheckService.class), registry);
        new OutboxBacklogMonitor(mock(OutboxEventRepository.class), Clock.systemUTC(), registry);
        new InterestRunner(mock(InterestService.class), registry);
        new HoldExpiryRunner(mock(HoldService.class), registry);
        new ScheduledTransferRunner(mock(ScheduledTransferService.class), registry, Clock.systemUTC());
        return registry.scrape();
    }

    @Test
    @DisplayName("every metric a rule names is one the application exports, before anything has happened")
    void everyMetricNamedIsExported() {
        Set<String> named = new TreeSet<>();
        for (Map<String, Object> rule : rules) {
            Matcher matcher = METRIC.matcher((String) rule.get("expr"));
            while (matcher.find()) {
                named.add(matcher.group());
            }
        }
        assertThat(named).as("the rules name some of the application's own metrics").isNotEmpty();

        Set<String> exported = new TreeSet<>();
        scrape().lines().filter(line -> !line.startsWith("#"))
                .map(line -> line.split("[{ ]", 2)[0])
                .forEach(exported::add);

        assertThat(exported).containsAll(named);
    }

    @Test
    @DisplayName("every rule says how bad it is and what it means")
    void everyRuleIsDescribed() {
        assertThat(rules).isNotEmpty().allSatisfy(rule -> {
            assertThat(rule.get("alert")).as("name").isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertThat(labels.get("severity")).as(rule.get("alert") + " severity").isIn("critical", "warning");
            assertThat(annotations).as(rule.get("alert") + " annotations").containsKeys("summary", "description");
        });
    }
}
