package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.knowledge.dto.AgentExpertProfileUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentRuntimeConfigUpdateRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentGovernanceService;
import com.novelanalyzer.modules.knowledge.vo.AgentExpertProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentCacheTokenStatsVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeAgentGovernanceServiceTest {

    @Test
    void shouldReturnDefaultRuntimePolicy() {
        KnowledgeAgentGovernanceService service = newService();

        AgentRuntimeConfigVO config = service.runtimeConfig();

        assertThat(config.getReasoningModeDefault()).isEqualTo("fast");
        assertThat(config.getMaxParallelSpecialists()).isEqualTo(3);
        assertThat(config.getMaxTotalInputTokens()).isEqualTo(1_000_000);
        assertThat(config.getMaxFinalOutputTokensFast()).isEqualTo(4000);
        assertThat(config.getMaxEvidenceItems()).isEqualTo(30);
        assertThat(config.getEnableIntentCache()).isTrue();
        assertThat(config.getEnableSpecialistCache()).isFalse();
    }

    @Test
    void shouldUpdateRuntimePolicyWithValidation() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("5");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("maxParallelSpecialists", request);

        assertThat(updated.getMaxParallelSpecialists()).isEqualTo(5);
        assertThat(service.runtimeConfig().getMaxParallelSpecialists()).isEqualTo(5);

        AgentRuntimeConfigUpdateRequest invalid = new AgentRuntimeConfigUpdateRequest();
        invalid.setValue("0");
        assertThatThrownBy(() -> service.updateRuntimeConfig("maxParallelSpecialists", invalid))
            .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.updateRuntimeConfig("unknownKey", request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldAllowZeroFinalOutputTokensToDisableWorkerSideCap() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("0");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("maxFinalOutputTokensDeep", request);

        assertThat(updated.getMaxFinalOutputTokensDeep()).isZero();
        assertThat(service.runtimeConfig().getMaxFinalOutputTokensDeep()).isZero();
    }

    @Test
    void shouldListDefaultExpertProfiles() {
        KnowledgeAgentGovernanceService service = newService();

        List<AgentExpertProfileVO> profiles = service.listExpertProfiles();

        assertThat(profiles).extracting(AgentExpertProfileVO::getExpertName)
            .contains(
                "market_scan",
                "author_strategy",
                "opening_strategy",
                "outline",
                "reader_risk",
                "editor",
                "supervisor"
            );
        assertThat(profiles)
            .filteredOn(profile -> "market_scan".equals(profile.getExpertName()))
            .singleElement()
            .satisfies(profile -> {
                assertThat(profile.getEnabled()).isTrue();
                assertThat(profile.getMaxTokens()).isGreaterThan(0);
                assertThat(profile.getTriggerIntents()).contains("market_scan");
                assertThat(profile.getAllowedTools()).contains("rank.lookup");
            });
    }

    @Test
    void shouldUpdateExpertProfileAndRejectUnknownExpert() {
        KnowledgeAgentGovernanceService service = newService();
        AgentExpertProfileUpdateRequest request = new AgentExpertProfileUpdateRequest();
        request.setEnabled(false);
        request.setPriority(10);
        request.setMaxTokens(1200);
        request.setMaxToolCalls(2);
        request.setTriggerIntents(List.of("market_scan"));
        request.setTriggerTasks(List.of("market_scan"));
        request.setAllowedTools(List.of("rank.lookup", "knowledge.vector_search"));
        request.setPromptVersion("v2");
        request.setEvalSuiteId("market-suite");

        AgentExpertProfileVO updated = service.updateExpertProfile("market_scan", request);

        assertThat(updated.getEnabled()).isFalse();
        assertThat(updated.getPriority()).isEqualTo(10);
        assertThat(updated.getMaxTokens()).isEqualTo(1200);
        assertThat(updated.getMaxToolCalls()).isEqualTo(2);
        assertThat(updated.getAllowedTools()).containsExactly("rank.lookup", "knowledge.vector_search");
        assertThat(updated.getPromptVersion()).isEqualTo("v2");
        assertThat(updated.getEvalSuiteId()).isEqualTo("market-suite");

        assertThat(service.listExpertProfiles())
            .filteredOn(profile -> "market_scan".equals(profile.getExpertName()))
            .singleElement()
            .extracting(AgentExpertProfileVO::getEnabled)
            .isEqualTo(false);

        assertThatThrownBy(() -> service.updateExpertProfile("unknown_expert", request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldAggregateCacheAndTokenStatsFromTraceJson() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, question, status, result_json) values(?, ?, ?, ?, ?)",
            "trace-a",
            1L,
            "market",
            "answered",
            """
                {
                  "tokenUsed": 120,
                  "cacheEvents": [
                    {"status": "HIT"},
                    {"status": "MISS"}
                  ],
                  "trace": {
                    "tokenUsage": {
                      "byNode": {"route_experts": 11},
                      "byExpert": {"market_scan": 22}
                    }
                  }
                }
                """
        );
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, question, status, result_json) values(?, ?, ?, ?, ?)",
            "trace-b",
            1L,
            "outline",
            "answered",
            """
                {
                  "tokenUsed": 80,
                  "cacheStats": {"hits": 2, "misses": 1},
                  "tokenUsage": {
                    "byNode": {"compose_answer": 33},
                    "byExpert": {"outline": 44}
                  }
                }
                """
        );
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );

        AgentCacheTokenStatsVO stats = service.cacheTokenStats();

        assertThat(stats.getTraceCount()).isEqualTo(2);
        assertThat(stats.getCacheHits()).isEqualTo(3);
        assertThat(stats.getCacheMisses()).isEqualTo(2);
        assertThat(stats.getTotalTokens()).isEqualTo(200);
        assertThat(stats.getTokenByNode()).containsEntry("route_experts", 11L).containsEntry("compose_answer", 33L);
        assertThat(stats.getTokenByExpert()).containsEntry("market_scan", 22L).containsEntry("outline", 44L);
    }

    @Test
    void shouldPreferPersistedCacheAndTokenTelemetryWhenPresent() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createTelemetryTables(jdbcTemplate);
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, question, status, result_json) values(?, ?, ?, ?, ?)",
            "trace-a",
            1L,
            "market",
            "answered",
            """
                {
                  "tokenUsed": 999,
                  "cacheEvents": [{"status": "HIT"}],
                  "trace": {"tokenUsage": {"byNode": {"stale": 999}, "byExpert": {"stale": 999}}}
                }
                """
        );
        jdbcTemplate.update(
            "insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name, cache_status, prompt_prefix_stable) values(?, ?, ?, ?, ?, ?)",
            "trace-a",
            "intent",
            "route_intent",
            null,
            "HIT",
            true
        );
        jdbcTemplate.update(
            "insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name, cache_status, prompt_prefix_stable) values(?, ?, ?, ?, ?, ?)",
            "trace-a",
            "tool",
            "retrieve_evidence",
            "market_scan",
            "MISS",
            false
        );
        jdbcTemplate.update(
            "insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name, cache_status, prompt_prefix_stable) values(?, ?, ?, ?, ?, ?)",
            "trace-b",
            "specialist",
            "run_specialists",
            "outline",
            "HIT",
            true
        );
        jdbcTemplate.update(
            "insert into ai_agent_token_metric(trace_id, node_name, expert_name, token_count) values(?, ?, ?, ?)",
            "trace-a",
            "route_experts",
            "market_scan",
            11
        );
        jdbcTemplate.update(
            "insert into ai_agent_token_metric(trace_id, node_name, expert_name, token_count) values(?, ?, ?, ?)",
            "trace-a",
            "compose_answer",
            null,
            33
        );
        jdbcTemplate.update(
            "insert into ai_agent_token_metric(trace_id, node_name, expert_name, token_count) values(?, ?, ?, ?)",
            "trace-b",
            "run_specialists",
            "outline",
            22
        );
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );

        AgentCacheTokenStatsVO stats = service.cacheTokenStats();

        assertThat(stats.getTraceCount()).isEqualTo(2);
        assertThat(stats.getCacheHits()).isEqualTo(2);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getTotalTokens()).isEqualTo(66);
        assertThat(stats.getPromptPrefixStableRate()).isEqualTo(0.6667);
        assertThat(stats.getTokenByNode())
            .containsEntry("route_experts", 11L)
            .containsEntry("compose_answer", 33L)
            .containsEntry("run_specialists", 22L)
            .doesNotContainEntry("stale", 999L);
        assertThat(stats.getTokenByExpert())
            .containsEntry("market_scan", 11L)
            .containsEntry("outline", 22L)
            .doesNotContainEntry("stale", 999L);
    }

    private static KnowledgeAgentGovernanceService newService() {
        return new KnowledgeAgentGovernanceService(newSystemConfigService(), new ObjectMapper());
    }

    private static SystemConfigService newSystemConfigService() {
        SystemConfigRepository repository = mock(SystemConfigRepository.class);
        ConfigSecretService secretService = mock(ConfigSecretService.class);
        Map<String, SystemConfigEntity> configs = new HashMap<>();

        when(repository.findByKey(any())).thenAnswer(invocation ->
            Optional.ofNullable(configs.get(invocation.getArgument(0)))
        );
        when(repository.saveOrUpdate(any(SystemConfigEntity.class))).thenAnswer(invocation -> {
            SystemConfigEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId((long) configs.size() + 1);
            }
            if (entity.getEditable() == null) {
                entity.setEditable(1);
            }
            configs.put(entity.getConfigKey(), entity);
            return entity;
        });
        when(secretService.hasSecret(any())).thenReturn(false);
        when(secretService.decryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretService.encryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretService.isMaskedValue(any())).thenReturn(false);
        when(secretService.maskValue(any())).thenReturn("");

        ObjectMapper objectMapper = new ObjectMapper();
        return new SystemConfigService(repository, objectMapper, secretService);
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent-governance-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_agent_trace (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(120)," +
            "user_id bigint," +
            "question varchar(500)," +
            "status varchar(30)," +
            "result_json clob," +
            "created_at timestamp default current_timestamp)");
        return jdbcTemplate;
    }

    private static void createTelemetryTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table ai_agent_cache_event (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(120)," +
            "cache_scope varchar(60)," +
            "node_name varchar(120)," +
            "expert_name varchar(120)," +
            "cache_status varchar(20)," +
            "prompt_prefix_stable boolean," +
            "created_at timestamp default current_timestamp)");
        jdbcTemplate.execute("create table ai_agent_token_metric (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(120)," +
            "node_name varchar(120)," +
            "expert_name varchar(120)," +
            "token_count bigint," +
            "created_at timestamp default current_timestamp)");
    }
}
