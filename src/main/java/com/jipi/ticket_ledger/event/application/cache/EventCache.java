package com.jipi.ticket_ledger.event.application.cache;

import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;

import java.time.Duration;
import java.util.Optional;

public interface EventCache {

    Optional<EventListCacheResponse> findEventList();

    Optional<EventDetailResponse> findEventDetail(Long eventId);

    void putEventList(EventListCacheResponse response);

    void putEventDetail(Long eventId, EventDetailResponse response);

    boolean tryAcquireRefreshLock(String key, String token, Duration ttl);

    void releaseRefreshLock(String key, String token);
}
