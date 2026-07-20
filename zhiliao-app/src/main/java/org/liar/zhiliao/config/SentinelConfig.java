package org.liar.zhiliao.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 限流熔断配置。
 *
 * <p>使用 {@code spring-cloud-starter-alibaba-sentinel} 作为 Starter，
 * {@code spring.cloud.sentinel.transport.*} 配置由 Starter 自动加载，
 * Dashboard 连接无需手动初始化。</p>
 *
 * <h3>Dashboard 使用</h3>
 * <ol>
 *   <li>启动 Dashboard：docker compose -f docker/local-dev.yml up -d sentinel-dashboard</li>
 *   <li>启动应用，打开 http://localhost:8858（账号 sentinel/sentinel）</li>
 *   <li>在 chat 资源上添加"流控规则" → QPS=5 → 即时生效</li>
 * </ol>
 */
@Slf4j
@Configuration
public class SentinelConfig {

    @PostConstruct
    public void init() {
        // 全局后备限流：防止突发流量打满服务器
        FlowRule globalRule = new FlowRule();
        globalRule.setResource("chat");
        globalRule.setCount(100);
        globalRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        globalRule.setLimitApp("default");

        FlowRuleManager.loadRules(List.of(globalRule));

        log.info("Sentinel initialized: resource=chat, qps=100");
    }

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }
}
