package com.onedata.portal.agentapi.auth;

/** Main backend owns token persistence; this module only consumes verified identities. */
public interface ServiceAccountAuthenticator {
    /** Returns the active platform SERVICE account ID, or rejects the credential. */
    String authenticate(String token);
}
