package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.user.application.model.ResponseMeDTO;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserQueryService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public ResponseMeDTO getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("일치하는 사용자가 없습니다."));
        return new ResponseMeDTO(user.getId(), user.getEmail(), user.getName(),
                user.getRole().name(), user.getStatus().name());
    }
}
