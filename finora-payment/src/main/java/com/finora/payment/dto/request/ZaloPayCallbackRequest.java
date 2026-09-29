package com.finora.payment.dto.request;

public record ZaloPayCallbackRequest(String data, String mac, Integer type) {}
