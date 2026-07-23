package com.jipi.ticket_ledger.featureflag.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;

public record QueueModeChangedEvent(QueueModeSnapshot current) {
}
