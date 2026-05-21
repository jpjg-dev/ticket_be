package com.jipi.ticket_ledger.auth.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TokenHasherTest {
    private final TokenHasher tokenHasher = new TokenHasher();

    @Test
    @DisplayName("같은 입력은 동일한 SHA-256 해시를 반환한다")
    void hash_returnsDeterministicValue() {
        String first = tokenHasher.hash("refresh-token");
        String second = tokenHasher.hash("refresh-token");

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    @DisplayName("다른 입력은 다른 해시를 반환한다")
    void hash_returnsDifferentValuesForDifferentTokens() {
        String first = tokenHasher.hash("refresh-token");
        String second = tokenHasher.hash("refresh-token-2");

        assertNotEquals(first, second);
    }
}

