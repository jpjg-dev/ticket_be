package com.jipi.ticket_ledger.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // id 조회
    Optional<User> findByEmail(String email);
}
