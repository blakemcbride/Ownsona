import ai.ownsona.oauth.OwnsonaConsentProvider
import ai.ownsona.oauth.OwnsonaUserAuthenticator
import org.kissweb.database.Connection
import org.kissweb.oauth.as.AsExtensions
import org.kissweb.restServer.MainServlet
import org.kissweb.restServer.UserCache
import org.kissweb.restServer.UserData
import java.util.function.Consumer

class KissInit {

    /**
     * Configure the system.
     */
    static void init() {

        MainServlet.readIniFile "application.ini", "main"

        // OAuth 2.1 authorization server extensions.  The AS is generic;
        // these two implementations supply the app-specific bits ---
        // verifying the user's login on the consent page and providing
        // human-readable scope descriptions.  Both must be registered
        // before any /oauth/* request arrives.
        AsExtensions.setUserAuthenticator(new OwnsonaUserAuthenticator())
        AsExtensions.setConsentProvider(new OwnsonaConsentProvider())

        // Example of how to specify a method that is allowed without authentication
    //    MainServlet.allowWithoutAuthentication("services.MyGroovyService", "addNumbers")

        // Set up a global logout handler that runs whenever any user logs out
        // This can be used for cleanup tasks like logging, closing resources, etc.
        UserCache.setLogoutHandler({ UserData ud ->
            // Example: Log the logout event
            println "User ${ud.getUsername()} (ID: ${ud.getUserId()}) is logging out"

            // Add any custom cleanup code here
            // Examples:
            // - Close user-specific resources
            // - Update database logout timestamp
            // - Send notifications
            // - Clean up temporary files
        } as Consumer<UserData>)

    }

    /**
     * Code to run once the database is open but before the app is running.
     */
    static void init2(Connection db) {
        // If you use db, make sure you commit.
    }
}
