package com.nexusagent.context.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.context")
public class ContextProperties {

    private int defaultBudgetChars = 4000;
    private int maxBudgetChars = 12000;

    public int getDefaultBudgetChars() {
        return defaultBudgetChars;
    }

    public void setDefaultBudgetChars(int defaultBudgetChars) {
        this.defaultBudgetChars = defaultBudgetChars;
    }

    public int getMaxBudgetChars() {
        return maxBudgetChars;
    }

    public void setMaxBudgetChars(int maxBudgetChars) {
        this.maxBudgetChars = maxBudgetChars;
    }
}
