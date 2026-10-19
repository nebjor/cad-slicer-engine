package com.slicercore.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void happyPathRunsFromNewToShipped() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.IN_REVIEW)).isTrue();
        assertThat(OrderStatus.IN_REVIEW.canTransitionTo(OrderStatus.APPROVED)).isTrue();
        assertThat(OrderStatus.APPROVED.canTransitionTo(OrderStatus.PRINTING)).isTrue();
        assertThat(OrderStatus.PRINTING.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
    }

    @Test
    void printingNeedsReviewAndApprovalFirst() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.PRINTING)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PRINTING)).isFalse();
        assertThat(OrderStatus.IN_REVIEW.canTransitionTo(OrderStatus.PRINTING)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.APPROVED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
    }

    @Test
    void cancellationIsPossibleUntilPrintingStarts() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.IN_REVIEW.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.APPROVED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PRINTING.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoExit() {
        assertThat(OrderStatus.SHIPPED.allowedTransitions()).isEmpty();
        assertThat(OrderStatus.CANCELLED.allowedTransitions()).isEmpty();
    }
}
