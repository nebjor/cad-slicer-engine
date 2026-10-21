package com.slicercore.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountSessionRepository extends JpaRepository<AccountSession, UUID> {

    Optional<AccountSession> findByTokenHash(String tokenHash);

    long deleteByTokenHash(String tokenHash);

    long deleteByAccount(Account account);

    /** Nightly purge of sessions nobody can use any more. Returns how many went. */
    @Modifying
    @Query("delete from AccountSession s where s.expiresAt < :now")
    int deleteExpired(Instant now);
}
