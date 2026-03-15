package io.github.massimilianopili.mcp.token;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.token.enabled", havingValue = "true", matchIfMissing = false)
@Import({TokenConfig.class, TokenTools.class})
public class TokenToolsAutoConfiguration {
}
