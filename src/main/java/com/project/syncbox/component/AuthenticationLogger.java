package com.project.syncbox.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationLogger {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationLogger.class);

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent success) {
        String username = success.getAuthentication().getName();
        log.info("Login successful username={}", username);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent failures) {
        String username = (String) failures.getAuthentication().getPrincipal();
        log.warn("Login failed username={} reason=\"{}\"", username, failures.getException().getMessage());
    }
}
