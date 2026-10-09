package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.user.domain.SocialAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
    void deleteAllByUserId(Long userId);
}
