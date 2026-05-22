package com.jipi.ticket_ledger.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "security.csrf-origin")
public class CsrfOriginProperties {

    private List<String> allowedOrigins = new ArrayList<>();
    public CsrfOriginProperties() {
    }

    public CsrfOriginProperties(List<String> allowedOrigins) {
        this.allowedOrigins = new ArrayList<>(allowedOrigins);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = new ArrayList<>(allowedOrigins);
    }
}
