package io.github.massimilianopili.mcp.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.token")
public class TokenProperties {

    private boolean enabled = false;

    // PostgreSQL
    private String dbUrl = "jdbc:postgresql://postgres:5432/embeddings";
    private String dbUsername = "postgres";
    private String dbCredential;

    // Default model for cost calculation
    private String defaultModel = "claude-opus-4";

    // Pricing per 1M tokens (USD) — Opus 4
    private double opusInputPrice = 15.0;
    private double opusOutputPrice = 75.0;
    private double opusCacheReadPrice = 1.5;
    private double opusCacheWritePrice = 3.75;

    // Pricing per 1M tokens (USD) — Sonnet 4
    private double sonnetInputPrice = 3.0;
    private double sonnetOutputPrice = 15.0;
    private double sonnetCacheReadPrice = 0.3;
    private double sonnetCacheWritePrice = 0.75;

    // Budget
    private double budgetWarningThreshold = 0.8;
    private int reportMaxRows = 20;

    // Getters and setters

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDbUrl() { return dbUrl; }
    public void setDbUrl(String dbUrl) { this.dbUrl = dbUrl; }

    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }

    public String getDbCredential() { return dbCredential; }
    public void setDbCredential(String dbCredential) { this.dbCredential = dbCredential; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public double getOpusInputPrice() { return opusInputPrice; }
    public void setOpusInputPrice(double opusInputPrice) { this.opusInputPrice = opusInputPrice; }

    public double getOpusOutputPrice() { return opusOutputPrice; }
    public void setOpusOutputPrice(double opusOutputPrice) { this.opusOutputPrice = opusOutputPrice; }

    public double getOpusCacheReadPrice() { return opusCacheReadPrice; }
    public void setOpusCacheReadPrice(double opusCacheReadPrice) { this.opusCacheReadPrice = opusCacheReadPrice; }

    public double getOpusCacheWritePrice() { return opusCacheWritePrice; }
    public void setOpusCacheWritePrice(double opusCacheWritePrice) { this.opusCacheWritePrice = opusCacheWritePrice; }

    public double getSonnetInputPrice() { return sonnetInputPrice; }
    public void setSonnetInputPrice(double sonnetInputPrice) { this.sonnetInputPrice = sonnetInputPrice; }

    public double getSonnetOutputPrice() { return sonnetOutputPrice; }
    public void setSonnetOutputPrice(double sonnetOutputPrice) { this.sonnetOutputPrice = sonnetOutputPrice; }

    public double getSonnetCacheReadPrice() { return sonnetCacheReadPrice; }
    public void setSonnetCacheReadPrice(double sonnetCacheReadPrice) { this.sonnetCacheReadPrice = sonnetCacheReadPrice; }

    public double getSonnetCacheWritePrice() { return sonnetCacheWritePrice; }
    public void setSonnetCacheWritePrice(double sonnetCacheWritePrice) { this.sonnetCacheWritePrice = sonnetCacheWritePrice; }

    public double getBudgetWarningThreshold() { return budgetWarningThreshold; }
    public void setBudgetWarningThreshold(double budgetWarningThreshold) { this.budgetWarningThreshold = budgetWarningThreshold; }

    public int getReportMaxRows() { return reportMaxRows; }
    public void setReportMaxRows(int reportMaxRows) { this.reportMaxRows = reportMaxRows; }
}
