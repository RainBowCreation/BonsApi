package net.rainbowcreation.bonsai.api;

import net.rainbowcreation.bonsai.api.query.Query;

public interface BonsaiTable<T> {
    /**
     * Default get wait forever.
     */
    default T get(String key) {
        return getAsync(key).get();
    }
    BonsaiFuture<T> getAsync(String key);

    /**
     * Default set just fire and forget.
     */
    default void set(String key, T value) {
        setAsync(key,value);
    }
    BonsaiFuture<Void> setAsync(String key, T value);

    /**
     * Default delete just fire amd forget.
     */
    default void delete(String key) {
        deleteAsync(key);
    }
    BonsaiFuture<Void> deleteAsync(String key);

    /**
     * For String table name & typecast
     */
    default <R> BonsaiFuture<R> getAsync(String key, Class<R> type) {
        return getAsync(key).map(obj -> {
            if (obj == null) return null;
            return type.cast(obj);
        });
    }

    /**
     * Default exists wait forever.
     */
    default Boolean exists(String key) {
        return existsAsync(key).get();
    }
    BonsaiFuture<Boolean> existsAsync(String key);
    Query<T> find();
}