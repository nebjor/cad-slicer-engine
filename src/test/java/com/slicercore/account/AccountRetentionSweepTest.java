package com.slicercore.account;

import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountRetentionSweepTest {

    @Test
    void purgesExpiredTokensAndSessionsAsOfNow() {
        LoginTokenRepository tokens = mock(LoginTokenRepository.class);
        AccountSessionRepository sessions = mock(AccountSessionRepository.class);
        MutableClock clock = MutableClock.at("2026-10-22T03:30:00Z");
        when(tokens.deleteExpired(clock.instant())).thenReturn(2);
        when(sessions.deleteExpired(clock.instant())).thenReturn(3);

        AccountRetentionSweep sweep = new AccountRetentionSweep(tokens, sessions, clock);

        assertThat(sweep.sweep()).isEqualTo(5);
        assertThat(sweep.subject()).isEqualTo("expired login tokens and sessions");
    }
}
