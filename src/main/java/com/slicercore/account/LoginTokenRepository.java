package com.slicercore.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoginTokenRepository extends JpaRepository<LoginToken, UUID> {

    Optional<LoginToken> findByTokenHash(String tokenHash);

    long deleteByEmail(String email);

    /** Nightly purge: used or not, an expired token is of no use to anyone. Returns how many went. */
    @Modifying
    @Query("delete from LoginToken t where t.expiresAt < :now")
    int deleteExpired(Instant now);
}
