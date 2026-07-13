package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

@ExtendWith(OutputCaptureExtension.class)
class TossPaymentClientTest {

    private static final String SECRET_KEY = "super-secret-toss-key";

    private TossPaymentClient clientBoundTo(MockRestServiceServer[] serverHolder) {
        RestClient.Builder builder = RestClient.builder();
        serverHolder[0] = MockRestServiceServer.bindTo(builder).build();
        return new TossPaymentClient(builder.build(), SECRET_KEY);
    }

    @Test
    @DisplayName("confirm 실패 시 표준 형식으로 로깅하고(operation/outcome/마스킹) 원래 예외를 재전파한다")
    void confirmFailureLogsStandardFields(CapturedOutput output) {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        TossPaymentClient client = clientBoundTo(serverHolder);
        serverHolder[0].expect(requestTo("/v1/payments/confirm")).andRespond(withServerError());

        assertThrows(PaymentGatewayException.class,
                () -> client.confirm("test_pk_123456789", "order-1", 1000, "confirm:order-1"));

        serverHolder[0].verify();
        String logs = output.getOut();
        assertThat(logs).contains("event=TOSS_CALL_FAIL");
        assertThat(logs).contains("operation=CONFIRM");
        assertThat(logs).contains("orderId=order-1");
        assertThat(logs).contains("outcome=HTTP_ERROR");
        assertThat(logs).contains("httpStatus=500");
        // 결제키는 앞 6자만 노출, 나머지는 마스킹
        assertThat(logs).contains("paymentKeyMasked=test_p");
        assertThat(logs).doesNotContain("test_pk_123456789");
        // 비밀키/인증헤더는 절대 로그에 남지 않는다
        assertThat(logs).doesNotContain(SECRET_KEY);
    }

    @Test
    @DisplayName("cancel 실패 시 operation=CANCEL, orderId 미보유는 N/A 로 남긴다")
    void cancelFailureLogsOperationAndNaOrderId(CapturedOutput output) {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        TossPaymentClient client = clientBoundTo(serverHolder);
        serverHolder[0].expect(requestTo("/v1/payments/test_pk_9999/cancel")).andRespond(withServerError());

        assertThrows(PaymentGatewayException.class,
                () -> client.cancel("test_pk_9999", "reason", "KRW", "cancel:1"));

        serverHolder[0].verify();
        String logs = output.getOut();
        assertThat(logs).contains("event=TOSS_CALL_FAIL");
        assertThat(logs).contains("operation=CANCEL");
        assertThat(logs).contains("orderId=N/A");
        assertThat(logs).contains("idempotencyKey=cancel:1");
        assertThat(logs).doesNotContain(SECRET_KEY);
    }
}
