package io.github.massimilianopili.mcp.token;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(TokenProperties.class)
public class TokenConfig {

    @Bean(name = "tokenDataSource")
    public HikariDataSource tokenDataSource(TokenProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getDbUrl());
        ds.setUsername(props.getDbUsername());
        if (props.getDbCredential() != null && !props.getDbCredential().isBlank()) {
            ds.setPassword(props.getDbCredential());
        }
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        ds.setPoolName("token-pool");
        return ds;
    }

    @Bean(name = "costModel")
    public CostModel costModel(TokenProperties props) {
        Map<String, double[]> pricing = new HashMap<>();
        pricing.put("claude-opus-4", new double[]{
                props.getOpusInputPrice(), props.getOpusOutputPrice(),
                props.getOpusCacheReadPrice(), props.getOpusCacheWritePrice()
        });
        pricing.put("claude-sonnet-4", new double[]{
                props.getSonnetInputPrice(), props.getSonnetOutputPrice(),
                props.getSonnetCacheReadPrice(), props.getSonnetCacheWritePrice()
        });
        pricing.put("default", new double[]{
                props.getOpusInputPrice(), props.getOpusOutputPrice(),
                props.getOpusCacheReadPrice(), props.getOpusCacheWritePrice()
        });
        return new CostModel(pricing);
    }
}
