package io.github.massimilianopili.mcp.token;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class TokenTools {

    private static final Logger log = LoggerFactory.getLogger(TokenTools.class);

    // Whitelist for GROUP BY columns — prevents SQL injection
    private static final Map<String, String[]> GROUP_BY_MAP = Map.of(
            "session", new String[]{"session_id", "session_id"},
            "agent", new String[]{"scope_name", "scope_name"},
            "tool", new String[]{"scope_name", "scope_name"},
            "model", new String[]{"model", "model"},
            "day", new String[]{"DATE(created_at)", "day"},
            "hour", new String[]{"DATE_TRUNC('hour', created_at)", "hour"}
    );

    private final JdbcTemplate jdbc;
    private final TokenProperties props;
    private final CostModel costModel;

    public TokenTools(
            @Qualifier("tokenDataSource") DataSource dataSource,
            TokenProperties props,
            @Qualifier("costModel") CostModel costModel) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props;
        this.costModel = costModel;
    }

    @PostConstruct
    void initSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS token_ledger (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        session_id VARCHAR(100) NOT NULL,
                        entry_type VARCHAR(20) NOT NULL DEFAULT 'usage',
                        scope VARCHAR(50),
                        scope_name VARCHAR(200),
                        model VARCHAR(100),
                        input_tokens INTEGER DEFAULT 0,
                        output_tokens INTEGER DEFAULT 0,
                        cache_read_tokens INTEGER DEFAULT 0,
                        cache_write_tokens INTEGER DEFAULT 0,
                        cost_usd DOUBLE PRECISION,
                        wall_time_ms BIGINT,
                        note TEXT,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )""");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_token_ledger_session ON token_ledger (session_id, created_at DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_token_ledger_scope ON token_ledger (scope, scope_name, created_at DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_token_ledger_type ON token_ledger (entry_type, created_at DESC)");

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS token_budgets (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        budget_key VARCHAR(200) NOT NULL UNIQUE,
                        max_cost_usd DOUBLE PRECISION NOT NULL,
                        period VARCHAR(20) DEFAULT 'session',
                        active BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )""");

            log.info("Token tools: schema initialized (token_ledger + token_budgets)");
        } catch (Exception e) {
            log.error("Token tools: schema init failed: {}", e.getMessage());
        }
    }

    // ── Tool 1: meta_token_record ───────────────────────────────────────

    @ReactiveTool(name = "meta_token_record",
            description = "Record token consumption for the current session. Call after expensive operations "
                    + "or at session end to track spending. Computes cost automatically from model pricing. "
                    + "scope: 'tool' (single tool call), 'agent' (agent run), 'turn' (conversation turn), 'session' (cumulative).")
    public Mono<String> tokenRecord(
            @ToolParam(description = "Session ID") String sessionId,
            @ToolParam(description = "Input tokens consumed") int inputTokens,
            @ToolParam(description = "Output tokens consumed") int outputTokens,
            @ToolParam(description = "Scope: 'tool', 'agent', 'turn', or 'session'", required = false) String scope,
            @ToolParam(description = "Scope name: tool name, agent type, or null for session-level", required = false) String scopeName,
            @ToolParam(description = "Model name (default: configured default)", required = false) String model,
            @ToolParam(description = "Cache read tokens", required = false) Integer cacheReadTokens,
            @ToolParam(description = "Cache write tokens", required = false) Integer cacheWriteTokens,
            @ToolParam(description = "Wall-clock time in milliseconds", required = false) Long wallTimeMs,
            @ToolParam(description = "Optional note", required = false) String note) {
        return Mono.fromCallable(() -> {
            String effectiveModel = (model != null && !model.isBlank()) ? model : props.getDefaultModel();
            String effectiveScope = (scope != null && !scope.isBlank()) ? scope : "session";
            int cacheRead = cacheReadTokens != null ? cacheReadTokens : 0;
            int cacheWrite = cacheWriteTokens != null ? cacheWriteTokens : 0;

            double costUsd = costModel.computeCost(effectiveModel, inputTokens, outputTokens, cacheRead, cacheWrite);

            jdbc.update("""
                    INSERT INTO token_ledger (session_id, entry_type, scope, scope_name, model,
                        input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                        cost_usd, wall_time_ms, note)
                    VALUES (?, 'usage', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    sessionId, effectiveScope, scopeName, effectiveModel,
                    inputTokens, outputTokens, cacheRead, cacheWrite,
                    costUsd, wallTimeMs, note);

            // Running session total
            Map<String, Object> totals = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(cost_usd), 0) AS total_cost, COALESCE(SUM(wall_time_ms), 0) AS total_time, "
                            + "COALESCE(SUM(input_tokens), 0) AS total_input, COALESCE(SUM(output_tokens), 0) AS total_output "
                            + "FROM token_ledger WHERE session_id = ?", sessionId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recorded", true);
            result.put("costUsd", round6(costUsd));
            result.put("model", effectiveModel);
            result.put("scope", effectiveScope);
            result.put("scopeName", scopeName);

            Map<String, Object> sessionTotal = new LinkedHashMap<>();
            sessionTotal.put("totalCostUsd", round6(((Number) totals.get("total_cost")).doubleValue()));
            sessionTotal.put("totalInputTokens", ((Number) totals.get("total_input")).longValue());
            sessionTotal.put("totalOutputTokens", ((Number) totals.get("total_output")).longValue());
            sessionTotal.put("totalWallTimeMs", ((Number) totals.get("total_time")).longValue());
            result.put("sessionTotal", sessionTotal);

            return toJson(result);
        });
    }

    // ── Tool 2: meta_token_budget ───────────────────────────────────────

    @ReactiveTool(name = "meta_token_budget",
            description = "Set, check, or list token budgets. Budgets are soft limits (advisory, not enforced). "
                    + "Budget keys: 'session:<id>', 'daily', 'weekly', 'monthly', 'agent:<name>'. "
                    + "action: 'set' (create/update), 'check' (spend vs budget), 'list' (all active budgets).")
    public Mono<String> tokenBudget(
            @ToolParam(description = "Action: 'set', 'check', or 'list'") String action,
            @ToolParam(description = "Budget key: 'session:<id>', 'daily', 'agent:<name>'", required = false) String budgetKey,
            @ToolParam(description = "Maximum cost in USD (for 'set' action)", required = false) Double maxCostUsd,
            @ToolParam(description = "Budget period: 'session', 'daily', 'weekly', 'monthly' (for 'set')", required = false) String period) {
        return Mono.fromCallable(() -> {
            switch (action.toLowerCase()) {
                case "set" -> {
                    return budgetSet(budgetKey, maxCostUsd, period);
                }
                case "check" -> {
                    return budgetCheck(budgetKey);
                }
                case "list" -> {
                    return budgetList();
                }
                default -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "Unknown action: " + action + ". Use 'set', 'check', or 'list'.");
                    return toJson(err);
                }
            }
        });
    }

    private String budgetSet(String budgetKey, Double maxCostUsd, String period) {
        if (budgetKey == null || budgetKey.isBlank() || maxCostUsd == null) {
            return toJson(Map.of("error", "budgetKey and maxCostUsd are required for 'set' action"));
        }
        String effectivePeriod = (period != null && !period.isBlank()) ? period : "session";

        jdbc.update("""
                INSERT INTO token_budgets (budget_key, max_cost_usd, period, active, updated_at)
                VALUES (?, ?, ?, TRUE, NOW())
                ON CONFLICT (budget_key) DO UPDATE SET
                    max_cost_usd = EXCLUDED.max_cost_usd,
                    period = EXCLUDED.period,
                    active = TRUE,
                    updated_at = NOW()""",
                budgetKey, maxCostUsd, effectivePeriod);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("set", true);
        result.put("budgetKey", budgetKey);
        result.put("maxCostUsd", maxCostUsd);
        result.put("period", effectivePeriod);
        return toJson(result);
    }

    private String budgetCheck(String budgetKey) {
        if (budgetKey == null || budgetKey.isBlank()) {
            return toJson(Map.of("error", "budgetKey is required for 'check' action"));
        }

        List<Map<String, Object>> budgets = jdbc.queryForList(
                "SELECT budget_key, max_cost_usd, period FROM token_budgets WHERE budget_key = ? AND active = TRUE",
                budgetKey);

        if (budgets.isEmpty()) {
            return toJson(Map.of("error", "No active budget found for key: " + budgetKey));
        }

        Map<String, Object> budget = budgets.get(0);
        double maxCost = ((Number) budget.get("max_cost_usd")).doubleValue();
        String period = (String) budget.get("period");

        // Build WHERE clause based on budget key type
        double spent;
        if (budgetKey.startsWith("session:")) {
            String sessionId = budgetKey.substring("session:".length());
            spent = querySpent("session_id = ?", sessionId);
        } else if (budgetKey.startsWith("agent:")) {
            String agentName = budgetKey.substring("agent:".length());
            spent = querySpentInPeriod("scope = 'agent' AND scope_name = ?", agentName, period);
        } else {
            // daily, weekly, monthly — all entries in the period
            spent = querySpentInPeriod(null, null, period);
        }

        double remaining = maxCost - spent;
        double percentUsed = maxCost > 0 ? (spent / maxCost) * 100 : 0;

        String warning = null;
        if (percentUsed >= 100) {
            warning = "BUDGET EXCEEDED";
        } else if (percentUsed >= props.getBudgetWarningThreshold() * 100) {
            warning = "Approaching limit";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("budgetKey", budgetKey);
        result.put("maxCostUsd", round6(maxCost));
        result.put("spentUsd", round6(spent));
        result.put("remainingUsd", round6(remaining));
        result.put("percentUsed", Math.round(percentUsed * 10.0) / 10.0);
        result.put("period", period);
        if (warning != null) result.put("warning", warning);
        return toJson(result);
    }

    private double querySpent(String whereClause, String param) {
        String sql = "SELECT COALESCE(SUM(cost_usd), 0) FROM token_ledger WHERE " + whereClause;
        return jdbc.queryForObject(sql, Double.class, param);
    }

    private double querySpentInPeriod(String extraWhere, String extraParam, String period) {
        String timeFilter = switch (period.toLowerCase()) {
            case "daily" -> "created_at >= CURRENT_DATE";
            case "weekly" -> "created_at >= DATE_TRUNC('week', CURRENT_DATE)";
            case "monthly" -> "created_at >= DATE_TRUNC('month', CURRENT_DATE)";
            default -> "1=1"; // session — no time filter
        };

        String where = timeFilter;
        if (extraWhere != null) where += " AND " + extraWhere;

        String sql = "SELECT COALESCE(SUM(cost_usd), 0) FROM token_ledger WHERE " + where;
        if (extraParam != null) {
            return jdbc.queryForObject(sql, Double.class, extraParam);
        }
        return jdbc.queryForObject(sql, Double.class);
    }

    private String budgetList() {
        List<Map<String, Object>> budgets = jdbc.queryForList(
                "SELECT budget_key, max_cost_usd, period, created_at, updated_at FROM token_budgets WHERE active = TRUE ORDER BY budget_key");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", budgets.size());
        result.put("budgets", budgets);
        return toJson(result);
    }

    // ── Tool 3: meta_token_report ───────────────────────────────────────

    @ReactiveTool(name = "meta_token_report",
            description = "Query token spending with flexible grouping. Returns cost breakdowns by "
                    + "session, agent, tool, model, day, or hour. When includeQuality=true and groupBy='agent', "
                    + "cross-references metacognition_decisions for quality-per-dollar and efficiency metrics. "
                    + "groupBy options: 'session', 'agent', 'tool', 'model', 'day', 'hour'.")
    public Mono<String> tokenReport(
            @ToolParam(description = "Group by: 'session', 'agent', 'tool', 'model', 'day', 'hour'") String groupBy,
            @ToolParam(description = "Time window in hours (default: 24)", required = false) Integer windowHours,
            @ToolParam(description = "Filter to a specific session ID", required = false) String sessionId,
            @ToolParam(description = "Include quality-per-dollar analysis from metacognition data", required = false) Boolean includeQuality) {
        return Mono.fromCallable(() -> {
            String groupByLower = groupBy.toLowerCase();
            String[] groupSpec = GROUP_BY_MAP.get(groupByLower);
            if (groupSpec == null) {
                return toJson(Map.of("error", "Invalid groupBy: " + groupBy
                        + ". Valid options: " + String.join(", ", GROUP_BY_MAP.keySet())));
            }

            String groupColumn = groupSpec[0];
            String groupAlias = groupSpec[1];
            int window = (windowHours != null && windowHours > 0) ? windowHours : 24;
            Timestamp since = Timestamp.from(Instant.now().minus(window, ChronoUnit.HOURS));
            int maxRows = props.getReportMaxRows();

            // Additional scope filter for tool/agent
            String scopeFilter = "";
            if ("tool".equals(groupByLower)) scopeFilter = " AND scope = 'tool'";
            else if ("agent".equals(groupByLower)) scopeFilter = " AND scope = 'agent'";

            String sessionFilter = "";
            List<Object> params = new ArrayList<>();
            params.add(since);
            if (sessionId != null && !sessionId.isBlank()) {
                sessionFilter = " AND session_id = ?";
                params.add(sessionId);
            }

            boolean doQualityJoin = Boolean.TRUE.equals(includeQuality) && "agent".equals(groupByLower);

            String sql;
            if (doQualityJoin) {
                sql = String.format("""
                        SELECT %s AS %s,
                            SUM(t.input_tokens) AS total_input,
                            SUM(t.output_tokens) AS total_output,
                            SUM(t.cache_read_tokens) AS total_cache_read,
                            SUM(t.cache_write_tokens) AS total_cache_write,
                            SUM(t.cost_usd) AS total_cost,
                            SUM(t.wall_time_ms) AS total_time_ms,
                            COUNT(*) AS call_count,
                            AVG(m.outcome_quality) AS avg_quality,
                            CASE WHEN SUM(t.cost_usd) > 0 THEN AVG(m.outcome_quality) / SUM(t.cost_usd) END AS quality_per_dollar,
                            CASE WHEN SUM(t.wall_time_ms) > 0 THEN AVG(m.outcome_quality) / (SUM(t.wall_time_ms) / 60000.0) END AS quality_per_minute
                        FROM token_ledger t
                        LEFT JOIN metacognition_decisions m ON m.actual_agent = t.scope_name
                            AND m.created_at >= ?
                        WHERE t.created_at >= ?%s%s
                        GROUP BY %s
                        ORDER BY total_cost DESC
                        LIMIT ?""",
                        "t." + groupColumn, groupAlias, scopeFilter, sessionFilter, "t." + groupColumn);
                // Add the metacognition since param first, then ledger since
                List<Object> qualityParams = new ArrayList<>();
                qualityParams.add(since); // metacognition filter
                qualityParams.addAll(params); // ledger filter (since + optional sessionId)
                qualityParams.add(maxRows);
                params = qualityParams;
            } else {
                sql = String.format("""
                        SELECT %s AS %s,
                            SUM(input_tokens) AS total_input,
                            SUM(output_tokens) AS total_output,
                            SUM(cache_read_tokens) AS total_cache_read,
                            SUM(cache_write_tokens) AS total_cache_write,
                            SUM(cost_usd) AS total_cost,
                            SUM(wall_time_ms) AS total_time_ms,
                            COUNT(*) AS call_count
                        FROM token_ledger
                        WHERE created_at >= ?%s%s
                        GROUP BY %s
                        ORDER BY total_cost DESC
                        LIMIT ?""",
                        groupColumn, groupAlias, scopeFilter, sessionFilter, groupColumn);
                params.add(maxRows);
            }

            List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());

            // Compute derived metrics
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>(row);
                long callCount = ((Number) row.get("call_count")).longValue();
                double totalCost = ((Number) row.get("total_cost")).doubleValue();
                long totalTimeMs = row.get("total_time_ms") != null ? ((Number) row.get("total_time_ms")).longValue() : 0;

                entry.put("total_cost", round6(totalCost));
                entry.put("avg_cost_per_call", callCount > 0 ? round6(totalCost / callCount) : 0);
                entry.put("avg_time_per_call_ms", callCount > 0 ? totalTimeMs / callCount : 0);

                if (doQualityJoin) {
                    Double avgQ = row.get("avg_quality") != null ? ((Number) row.get("avg_quality")).doubleValue() : null;
                    Double qpd = row.get("quality_per_dollar") != null ? ((Number) row.get("quality_per_dollar")).doubleValue() : null;
                    Double qpm = row.get("quality_per_minute") != null ? ((Number) row.get("quality_per_minute")).doubleValue() : null;
                    if (avgQ != null) entry.put("avg_quality", round6(avgQ));
                    if (qpd != null) entry.put("quality_per_dollar", round6(qpd));
                    if (qpm != null) entry.put("quality_per_minute", round6(qpm));
                    if (avgQ != null && totalCost > 0 && totalTimeMs > 0) {
                        double efficiency = avgQ / (totalCost * totalTimeMs / 60000.0);
                        entry.put("efficiency", round6(efficiency));
                    }
                }
                entries.add(entry);
            }

            // Grand totals
            Map<String, Object> summary = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(cost_usd), 0) AS grand_total_cost, "
                            + "COALESCE(SUM(input_tokens), 0) AS grand_total_input, "
                            + "COALESCE(SUM(output_tokens), 0) AS grand_total_output, "
                            + "COALESCE(SUM(wall_time_ms), 0) AS grand_total_time_ms, "
                            + "COUNT(*) AS grand_total_entries "
                            + "FROM token_ledger WHERE created_at >= ?"
                            + (sessionId != null && !sessionId.isBlank() ? " AND session_id = ?" : ""),
                    sessionId != null && !sessionId.isBlank() ? new Object[]{since, sessionId} : new Object[]{since});
            summary.put("grand_total_cost", round6(((Number) summary.get("grand_total_cost")).doubleValue()));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("groupBy", groupByLower);
            result.put("windowHours", window);
            if (sessionId != null) result.put("sessionId", sessionId);
            result.put("entries", entries);
            result.put("summary", summary);
            return toJson(result);
        });
    }

    // ── JSON helpers ────────────────────────────────────────────────────

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            appendValue(sb, e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append(toJson((Map<String, Object>) value));
        } else if (value instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(",");
                first = false;
                if (item instanceof Map) {
                    sb.append(toJson((Map<String, Object>) item));
                } else {
                    appendValue(sb, item);
                }
            }
            sb.append("]");
        } else {
            sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
