package com.finora.notification.integration.push;

/** Receipt của một ticket: provider đã chuyển push tới APNs/FCM hay bị từ chối. */
public record PushReceipt(PushOutcome outcome, String errorCode) {
}
