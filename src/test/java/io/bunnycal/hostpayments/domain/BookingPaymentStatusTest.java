package io.bunnycal.hostpayments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BookingPaymentStatusTest {
    @Test
    void onlyCancellationAndFullRefundAreTerminal() {
        assertThat(BookingPaymentStatus.FAILED.terminal()).isFalse();
        assertThat(BookingPaymentStatus.CANCELLED.terminal()).isTrue();
        assertThat(BookingPaymentStatus.REFUNDED.terminal()).isTrue();
        assertThat(BookingPaymentStatus.SUCCEEDED.terminal()).isFalse();
        assertThat(BookingPaymentStatus.REFUND_REQUIRED.terminal()).isFalse();
        assertThat(BookingPaymentStatus.PARTIALLY_REFUNDED.terminal()).isFalse();
    }
}
