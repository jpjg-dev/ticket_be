package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserCommandService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public String signUp(String email, String password, String name) {
        if (userRepository.existsByEmail(email)) {
            log.warn("event={} email={} reason={}", LogEvents.USER_SIGNUP_REJECT, email, "DUPLICATE_EMAIL");
            throw new IllegalStateException("이미 존재하는 이메일입니다.");
        }
        User user = userRepository.save(new User(email, passwordEncoder.encode(password), name, clock.instant()));
        log.info("event={} userId={} email={} name={}", LogEvents.USER_SIGNUP_SUCCESS, user.getId(), email, name);
        return "회원가입이 완료되었습니다.";
    }
}
