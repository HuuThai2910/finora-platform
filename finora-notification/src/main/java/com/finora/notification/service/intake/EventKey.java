package com.finora.notification.service.intake;

/** Loại event (header {@code finora-event-type}) và version mà một handler hiểu. */
public record EventKey(String type, int version) {
}
