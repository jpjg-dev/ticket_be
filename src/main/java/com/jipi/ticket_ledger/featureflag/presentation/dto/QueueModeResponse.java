package com.jipi.ticket_ledger.featureflag.presentation.dto;

import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;

public record QueueModeResponse(
        QueueMode queueMode,
        QueueMode effectiveQueueMode,
        boolean automaticallyEnforced,
        long version
) {

    public static QueueModeResponse from(
            QueueModeSnapshot snapshot,
            QueueMode effectiveQueueMode,
            boolean automaticallyEnforced
    ) {
        return new QueueModeResponse(
                snapshot.queueMode(),
                effectiveQueueMode,
                automaticallyEnforced,
                snapshot.version()
        );
    }
}
