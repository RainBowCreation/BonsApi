package net.rainbowcreation.bonsai.api;

import net.rainbowcreation.bonsai.api.util.Stoppable;

import java.util.List;

public interface Bonsai extends Stoppable {
    /**
     * connect to a specific "Root" (Database).
     * @param dbName The identifier of the database in your config.
     * @param secret The auth secret (if required).
     */
    BonsaiRoot getRoot(String dbName, String secret) throws SecurityException;

    default BonsaiRoot getRoot(String dbName) throws SecurityException {
        return getRoot(dbName, null);
    }

    /**
     * @return List of available database names.
     */
    List<String> getRoots();
}
