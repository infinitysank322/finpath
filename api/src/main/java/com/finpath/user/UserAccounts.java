package com.finpath.user;

import java.util.UUID;

/** Sign-in side of the users table: resolve a verified Google identity to a FinPath user id. */
public interface UserAccounts {

    /**
     * Returns the id of the user with this email, creating the row on first sign-in. The caller
     * must only pass an email the identity provider marked verified; {@code email} is lower-case.
     * An existing user's name is left alone (they may have edited it).
     */
    UUID findOrCreateByVerifiedEmail(String email, String displayName);
}
