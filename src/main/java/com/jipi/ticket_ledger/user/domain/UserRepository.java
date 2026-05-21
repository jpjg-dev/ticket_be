package com.jipi.ticket_ledger.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // email 검증
    boolean existsByEmail(String email);
    // email 조회 및 찾기
    Optional<User> findByEmail(String email);
}
