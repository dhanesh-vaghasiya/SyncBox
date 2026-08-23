package com.project.syncbox.config;

import com.project.syncbox.handler.FileWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final FileWebSocketHandler fileWebSocketHandler;

    public WebSocketConfig(FileWebSocketHandler fileWebSocketHandler) {
        this.fileWebSocketHandler = fileWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(fileWebSocketHandler, "/ws/files").setAllowedOrigins("*");
    }
}
