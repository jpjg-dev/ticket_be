package com.jipi.ticket_ledger.event.application.model;

import java.util.List;

public record EventListCacheResponse(
        List<EventListResponse> events
) {
}
