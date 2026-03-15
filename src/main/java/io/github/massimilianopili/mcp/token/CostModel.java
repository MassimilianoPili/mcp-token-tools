package io.github.massimilianopili.mcp.token;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure Java pricing calculator for LLM token consumption.
 * Prices are per 1M tokens (USD). No Spring dependencies.
 */
public class CostModel {

    // model -> [inputPrice, outputPrice, cacheReadPrice, cacheWritePrice] per 1M tokens
    private final Map<String, double[]> pricing;

    public CostModel(Map<String, double[]> pricing) {
        this.pricing = new HashMap<>(pricing);
        if (!this.pricing.containsKey("default")) {
            this.pricing.put("default", new double[]{15.0, 75.0, 1.5, 3.75});
        }
    }

    public double computeCost(String model, int inputTokens, int outputTokens,
                              int cacheReadTokens, int cacheWriteTokens) {
        double[] prices = pricing.getOrDefault(
                model != null ? model : "default",
                pricing.get("default")
        );
        return (inputTokens * prices[0]
                + outputTokens * prices[1]
                + cacheReadTokens * prices[2]
                + cacheWriteTokens * prices[3]) / 1_000_000.0;
    }

    public Map<String, double[]> getPricing() {
        return Map.copyOf(pricing);
    }

    /**
     * Creates a CostModel with default pricing for Claude models.
     */
    public static CostModel withDefaults() {
        Map<String, double[]> defaults = new HashMap<>();
        defaults.put("claude-opus-4", new double[]{15.0, 75.0, 1.5, 3.75});
        defaults.put("claude-sonnet-4", new double[]{3.0, 15.0, 0.3, 0.75});
        defaults.put("default", new double[]{15.0, 75.0, 1.5, 3.75});
        return new CostModel(defaults);
    }
}
