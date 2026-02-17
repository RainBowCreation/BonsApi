package net.rainbowcreation.bonsai.api.connection;

/**
 * Callback interface for handling cache invalidation notifications from the server.
 * Called when the server sends a push notification (INVALIDATE operation with id=-1).
 */
@FunctionalInterface
public interface InvalidationCallback {
    /**
     * Called when receiving a cache invalidation notification.
     *
     * @param db    the database name
     * @param table the table name
     * @param key   the key to invalidate
     */
    void onInvalidate(String db, String table, String key);
}
