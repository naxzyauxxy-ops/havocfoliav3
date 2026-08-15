package gg.havoc.folia.api;

/**
 * Holder for the server-side implementation.
 *
 * <p>Set once during boot. A plugin that calls {@link HavocFoliaApi#get()} before the server has
 * finished starting gets a clear error rather than a null.
 */
public final class HavocFoliaApiHolder {

    private static volatile HavocFoliaApi instance;

    private HavocFoliaApiHolder() {
    }

    public static void set(HavocFoliaApi api) {
        if (instance != null) {
            throw new IllegalStateException("HavocFolia API is already initialised");
        }
        instance = api;
    }

    static HavocFoliaApi require() {
        HavocFoliaApi api = instance;
        if (api == null) {
            throw new IllegalStateException(
                "HavocFolia API is not available yet — are you calling it before the server finished starting,"
                    + " or are you running on a server that is not HavocFolia?");
        }
        return api;
    }

    public static boolean isAvailable() {
        return instance != null;
    }
}
