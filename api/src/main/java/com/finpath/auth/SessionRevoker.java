package com.finpath.auth;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Ends every way a deleted user could still authenticate: refresh tokens, live one-time codes, JWTs. */
@Component
public class SessionRevoker {

    private final RefreshTokenStore refreshTokens;
    private final OneTimeCodeStore codes;
    private final DeletedUsers deletedUsers;

    public SessionRevoker(RefreshTokenStore refreshTokens, OneTimeCodeStore codes, DeletedUsers deletedUsers) {
        this.refreshTokens = refreshTokens;
        this.codes = codes;
        this.deletedUsers = deletedUsers;
    }

    /** Call after the account's rows are committed as deleted. */
    public void revokeAllForDeletedUser(UUID userId) {
        deletedUsers.markDeleted(userId);
        refreshTokens.revokeAll(userId);
        codes.revokeAll(userId);
    }
}
