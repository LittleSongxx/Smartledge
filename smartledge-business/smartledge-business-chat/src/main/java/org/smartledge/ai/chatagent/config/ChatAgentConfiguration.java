package org.smartledge.ai.chatagent.config;

import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The runtime component owns execution; the business adapter owns tool and MySQL state. */
@Configuration
@EnableConfigurationProperties({ChatAgentProperties.class, TavilySearchProperties.class})
public class ChatAgentConfiguration { }
