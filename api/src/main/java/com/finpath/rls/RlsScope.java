package com.finpath.rls;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs background work (no request, so no authenticated principal) as one named user. {@link RlsTransactionManager}
 * consults this when {@code CurrentUser} is empty, so the transaction still runs as app_user with that user's
 * row-level-security context. The id is thread-local and always cleared in {@code finally}; it is only ever
 * supplied by server code (the consented-user list), never by a request.
 */
public final class RlsScope {

    private static final ThreadLocal<UUID> SCOPED = new ThreadLocal<>();

    private RlsScope() {
    }

    public static <T> T runAs(UUID userId, Supplier<T> work) {
        Objects.requireNonNull(userId, "userId");
        UUID previous = SCOPED.get();
        SCOPED.set(userId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                SCOPED.remove();
            } else {
                SCOPED.set(previous);
            }
        }
    }

    static Optional<UUID> current() {
        return Optional.ofNullable(SCOPED.get());
    }

    /** The authenticated principal if there is one, else the scoped user; empty (fail closed) otherwise. */
    public static Optional<UUID> principalOrScoped() {
        return com.finpath.auth.CurrentUser.id().or(RlsScope::current);
    }
}
