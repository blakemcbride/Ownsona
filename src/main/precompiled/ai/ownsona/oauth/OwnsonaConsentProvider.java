package ai.ownsona.oauth;

import org.kissweb.oauth.as.ConsentProvider;

/**
 * Consent-page text for OwnSona.  The server runs with no required
 * scopes, but the framework still asks for human-readable text for
 * whatever scopes a client happens to request (some MCP clients ask for
 * scopes by convention even when the server does not require any).
 *
 * <p>Returns the same brief description for every scope, since this is
 * an all-or-nothing personal memory store: granting any token grants
 * full access to the user's memories.  The display name ("OwnSona")
 * appears at the top of the login and consent pages.
 */
public final class OwnsonaConsentProvider implements ConsentProvider {

    /** Construct the provider; called once from {@code KissInit.groovy}. */
    public OwnsonaConsentProvider() {
    }

    @Override
    public String describeScope(String scope) {
        return "Access your OwnSona memory store.";
    }

    @Override
    public String getDisplayName() {
        return "OwnSona";
    }
}
