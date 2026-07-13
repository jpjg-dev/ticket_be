package com.jipi.ticket_ledger.auth.application.port.out;

public interface TokenHashEncoder {

    String hash(String token);
}
