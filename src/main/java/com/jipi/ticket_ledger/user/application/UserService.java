package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.presentation.dto.RequestLoginDTO;
import com.jipi.ticket_ledger.user.presentation.dto.RequestSignUpDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * {@link RequestSignUpDTO}
     *
     * @param request
     * @return
     */
    public String signUp(RequestSignUpDTO request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("이미 존재하는 이메일입니다.");
        }
        userRepository.save(new User(request.email(), passwordEncoder.encode(request.password()), request.name(), LocalDateTime.now()));
        log.info("회원가입 완료: email={}, name={}", request.email(), request.name());
        return "회원가입이 완료되었습니다.";
    }

    public void login(RequestLoginDTO request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 이메일입니다."));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalStateException("비밀번호가 일치하지 않습니다.");
        }
        log.info("로그인 성공: email={}", request.email());
    }
}
