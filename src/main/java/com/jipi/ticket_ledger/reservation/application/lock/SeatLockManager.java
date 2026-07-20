package com.jipi.ticket_ledger.reservation.application.lock;

import java.util.List;

public interface SeatLockManager {

    SeatLockHandle acquire(List<Long> seatIds);
}
