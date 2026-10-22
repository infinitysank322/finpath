package com.finpath.dpdp;

import com.finpath.auth.SessionRevoker;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** DELETE /me: erase the rows first, then end every credential, so nothing outlives the data. */
@Service
public class AccountDeletionService {

    private final DataSubjectService data;
    private final SessionRevoker sessions;

    public AccountDeletionService(DataSubjectService data, SessionRevoker sessions) {
        this.data = data;
        this.sessions = sessions;
    }

    public void delete(UUID userId) {
        data.delete(userId);
        sessions.revokeAllForDeletedUser(userId);
    }
}
