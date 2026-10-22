package com.finpath.dpdp;

import com.finpath.auth.SessionRevoker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes {@code users} rows (and, by cascade, their consent rows) whose owner never gave consent
 * or withdrew it, once the grace period has passed. Sign-in creates the users row before consent
 * can be asked for, so without this an abandoned sign-in would keep a name and an email forever.
 *
 * <p>Runs through the SECURITY DEFINER function {@code finpath_purge_unconsented_users} (V3),
 * deliberately outside any transaction so the RLS context (which would hide other users) is not
 * applied; the function is executable by the login role only, not by app_user. Each purged id then goes through SessionRevoker like DELETE /me. Logs a count only.
 * A database that is cold-starting just makes a run slow; a failed run is retried next hour.
 */
@Component
@EnableScheduling
public class UnconsentedUserPurge {

    private static final Logger log = LoggerFactory.getLogger(UnconsentedUserPurge.class);

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final SessionRevoker sessions;
    private final Duration grace;

    @Autowired
    public UnconsentedUserPurge(ObjectProvider<JdbcTemplate> jdbc, SessionRevoker sessions,
            @Value("${finpath.dpdp.purge-grace:7d}") Duration grace) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.grace = grace;
    }

    @Scheduled(fixedDelayString = "${finpath.dpdp.purge-interval:1h}", initialDelayString = "${finpath.dpdp.purge-initial-delay:10m}")
    public void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("unconsented-user purge failed ({})", e.getClass().getSimpleName());
        }
    }

    /** @return how many users were purged; 0 when no database is configured */
    public int purge() {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db == null) {
            return 0;
        }
        List<UUID> ids = db.queryForList(
                "SELECT unnest(finpath_purge_unconsented_users(? * interval '1 second'))", UUID.class, grace.toSeconds());
        // Same as DELETE /me: a purged user's JWT, refresh tokens and one-time codes stop working.
        ids.forEach(sessions::revokeAllForDeletedUser);
        int purged = ids.size();
        if (purged > 0) {
            log.info("purged {} user(s) without consent", purged);
        }
        return purged;
    }
}
