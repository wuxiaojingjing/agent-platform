package com.huawei.finance.persistence;

import com.huawei.finance.a2a.A2AConfiguration;
import com.huawei.finance.a2a.DelegationStore;
import com.huawei.finance.a2a.JdbcDelegationStore;
import com.huawei.finance.context.ContextProperties;
import com.huawei.finance.context.TurnStore;
import com.huawei.finance.context.store.JdbcTurnStore;
import com.huawei.finance.orchestrator.OrchestratorConfiguration;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.JdbcIntentPlanRepository;
import com.huawei.finance.orchestrator.task.JdbcTaskRepository;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.context.JdbcTaskContextStore;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.JdbcAgentLoopRepository;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@AutoConfigureAfter(JdbcTemplateAutoConfiguration.class)
@AutoConfigureBefore({OrchestratorConfiguration.class, A2AConfiguration.class})
@ConditionalOnBean(JdbcTemplate.class)
public class JdbcPersistenceAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public TaskRepository taskRepository(JdbcTemplate jdbc) { return new JdbcTaskRepository(jdbc); }

    @Bean @ConditionalOnMissingBean
    public IntentPlanRepository intentPlanRepository(JdbcTemplate jdbc) {
        return new JdbcIntentPlanRepository(jdbc);
    }

    @Bean @ConditionalOnMissingBean
    public TaskContextStore taskContextStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcTaskContextStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean @ConditionalOnMissingBean
    public AgentLoopRepository agentLoopRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcAgentLoopRepository(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    @ConditionalOnBean(ContextProperties.class)
    @ConditionalOnMissingBean
    public TurnStore turnStore(JdbcTemplate jdbc, ObjectProvider<RedissonClient> redis,
                               ContextProperties properties) {
        JdbcTurnStore authoritative = new JdbcTurnStore(jdbc);
        RedissonClient cache = redis.getIfAvailable();
        return cache == null ? authoritative
                : new HybridTurnStore(authoritative, cache, properties.getCacheTtl(), properties.getCachedTurns());
    }

    @Bean @ConditionalOnMissingBean
    public DelegationStore delegationStore(JdbcTemplate jdbc) { return new JdbcDelegationStore(jdbc); }
}
