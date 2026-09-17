package org.smartledge.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @description: 配置类
 * @author: Song
 **/

@Configuration
public class SwaggerConfiguration {

    @Bean
    public OpenAPI customOpenApi() {

        return new OpenAPI()
                .info(new Info()
                        .title("Smartledge API")
                        .version("1.0")
                        .description("Smartledge —— 多租户企业知识库 RAG 问答平台")
                        .contact(new Contact()
                                .name("Song")
                                .url("https://github.com/LittleSongxx")
                        ));
    }
}
