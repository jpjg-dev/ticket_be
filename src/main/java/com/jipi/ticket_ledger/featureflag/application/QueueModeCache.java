package com.jipi.ticket_ledger.featureflag.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;

import java.util.Optional;

public interface QueueModeCache {

    Optional<QueueModeSnapshot> findQueueMode();

    boolean putIfNewer(QueueModeSnapshot snapshot);
}
