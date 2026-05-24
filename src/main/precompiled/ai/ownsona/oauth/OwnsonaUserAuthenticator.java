package ai.ownsona.oauth;

import ai.ownsona.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.oauth.as.AuthenticatedUser;
import org.kissweb.oauth.as.UserAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Single-user credential check for the OAuth authorization server's login
 * page.  Compares the supplied username and password (in constant time)
 * against {@link Config#OWNSONA_LOGIN_USERNAME} and
 * {@link Config#OWNSONA_LOGIN_PASSWORD}, both required keys in
 * {@code application.ini}.
 *
 * <p>On success returns an {@link AuthenticatedUser} whose subject is
 * {@link Config#OWNSONA_USER_ID} --- the same value that stamps every
 * memory row in the database, so issued access tokens carry the user-id
 * the rest of the application already uses.
 *
 * <p>On failure returns {@code null}.  The framework logs the failure
 * with the username at INFO; we do not log the password, ever.
 */
public final class OwnsonaUserAuthenticator implements UserAuthenticator {

    private static final Logger logger = LogManager.getLogger(OwnsonaUserAuthenticator.class);

    /** Construct the authenticator; called once from {@code KissInit.groovy}. */
    public OwnsonaUserAuthenticator() {
    }

    @Override
    public AuthenticatedUser authenticate(String username, String password) {
        if (username == null || password == null)
            return null;
        if (!constantTimeEquals(username, Config.OWNSONA_LOGIN_USERNAME)
                || !constantTimeEquals(password, Config.OWNSONA_LOGIN_PASSWORD)) {
            logger.info("oauth login rejected for username={}", username);
            return null;
        }
        return new AuthenticatedUser(Config.OWNSONA_USER_ID);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
