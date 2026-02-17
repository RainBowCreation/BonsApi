package net.rainbowcreation.bonsai.api;

public interface BonsaiRoot {
    /**
     * Use a specific Class as a Table.
     * Preferred for Entity storage (Player, Hero, Guild).
     * Example: root.use(Player.class)
     */
    <T> BonsaiTable<T> use(Class<T> type);

    /**
     * Use a custom table name.
     * Preferred for mixed Config/Settings.
     * Example: root.use("global_config")
     */
    BonsaiTable<Object> use(String tableName);
}