package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import com.jipi.ticket_ledger.event.application.cache.CacheAsideLoader;
import com.jipi.ticket_ledger.event.application.cache.EventCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventQueryService {

    private static final String EVENT_LIST_KEY = "event:list::all";

    private final EventCache eventCache;
    private final EventDatabaseReader eventDatabaseReader;
    private final CacheAsideLoader cacheAsideLoader;

    public EventListCacheResponse getEvents() {
        return cacheAsideLoader.load(EVENT_LIST_KEY, eventCache::findEventList,
                eventDatabaseReader::getEvents, eventCache::putEventList);
    }

    public EventDetailResponse getEvent(Long eventId) {
        return cacheAsideLoader.load("event:detail::" + eventId,
                () -> eventCache.findEventDetail(eventId),
                () -> eventDatabaseReader.getEvent(eventId),
                value -> eventCache.putEventDetail(eventId, value));
    }
}
