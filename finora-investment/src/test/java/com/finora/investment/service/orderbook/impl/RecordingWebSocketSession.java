package com.finora.investment.service.orderbook.impl;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/** Phiên WebSocket giả: ghi lại tin đã gửi và mã đóng; {@code failSends} giả client đã rớt mạng. */
class RecordingWebSocketSession implements WebSocketSession {

    final BlockingQueue<WebSocketMessage<?>> sent = new LinkedBlockingQueue<>();
    final Map<String, Object> attributes = new ConcurrentHashMap<>();
    volatile CloseStatus closeStatus;
    volatile boolean failSends;

    private final String id;
    private volatile boolean open = true;

    RecordingWebSocketSession(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public URI getUri() {
        return URI.create("ws://localhost/ws/order-books/1");
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return new HttpHeaders();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
    }

    @Override
    public int getTextMessageSizeLimit() {
        return 64 * 1024;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return 64 * 1024;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        if (!open || failSends) {
            throw new IOException("Phiên đã đóng");
        }
        sent.add(message);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        close(CloseStatus.NORMAL);
    }

    @Override
    public void close(CloseStatus status) {
        open = false;
        if (closeStatus == null) {
            closeStatus = status;
        }
    }
}
