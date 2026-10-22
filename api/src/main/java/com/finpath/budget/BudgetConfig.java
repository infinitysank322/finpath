package com.finpath.budget;

import com.finpath.rules.RuleConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the rules thresholds (80% warn, 100% limit) unless another configuration already does. */
@Configuration(proxyBeanMethods = false)
class BudgetConfig {

    @Bean
    @ConditionalOnMissingBean
    RuleConfig ruleConfig() {
        return RuleConfig.defaults();
    }
}
