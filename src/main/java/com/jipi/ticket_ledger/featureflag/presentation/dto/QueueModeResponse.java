package com.jipi.ticket_ledger.featureflag.presentation.dto;

import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;

public record QueueModeResponse(QueueMode queueMode, long version) {

    public static QueueModeResponse from(QueueModeSnapshot snapshot) {
        return new QueueModeResponse(snapshot.queueMode(), snapshot.version());
    }
}
