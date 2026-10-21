package com.slicercore.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** @param email lower-cased, as stored */
    Optional<Account> findByEmail(String email);
}
