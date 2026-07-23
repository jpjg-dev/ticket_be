package com.jipi.ticket_ledger.reservation.application.lock;

public interface SeatLockHandle extends AutoCloseable {

    @Override
    void close();
}
