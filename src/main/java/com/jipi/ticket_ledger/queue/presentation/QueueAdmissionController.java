package com.jipi.ticket_ledger.queue.presentation;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionService;
import com.jipi.ticket_ledger.queue.presentation.dto.QueueAdmissionRequest;
import com.jipi.ticket_ledger.queue.presentation.dto.QueueAdmissionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/queue/admissions")
@RequiredArgsConstructor
public class QueueAdmissionController {

    private final QueueAdmissionService queueAdmissionService;
    private final QueueStatusStreamService queueStatusStreamService;

    @PostMapping
    public QueueAdmissionResponse enter(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid QueueAdmissionRequest request
    ) {
        return QueueAdmissionResponse.from(queueAdmissionService.enter(userId, request.scheduleId()));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long scheduleId,
            @RequestParam String queueToken
    ) {
        return queueStatusStreamService.open(userId, scheduleId, queueToken);
    }

    @DeleteMapping
    public void cancel(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long scheduleId,
            @RequestParam String queueToken
    ) {
        queueAdmissionService.cancel(userId, scheduleId, queueToken);
    }
}
