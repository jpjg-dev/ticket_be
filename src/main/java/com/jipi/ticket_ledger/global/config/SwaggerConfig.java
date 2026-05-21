package com.jipi.ticket_ledger.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info()
                        .title("TicketLedger API")
                        .version("v1")
                        .description("공연 예매/결제 시스템 API 문서")
                        .version("1.0.0"));
    }
}
