package net.rainbowcreation.bonsai.api.query;

import net.rainbowcreation.bonsai.api.BonsaiFuture;

import java.util.List;
import java.util.Map;

public interface Query<T> {
    Query<T> where(String field, QueryOp op, Object val);

    default Query<T> where(String field, Object val) {
        return where(field, QueryOp.EQ, val);
    }
    default Query<T> where(String field, String op, Object val) {
        return where(field, QueryOp.fromString(op), val);
    }

    /**
     * raw SQL query string, this override all other .where
     */
    Query<T> where(String rawQueryString);

    /**
     * complex query logic, this override all other .where
     */
    Query<T> filter(SearchCriteria criteria);

    Query<T> limit(int limit);
    Query<T> offset(int offset);

    Query<T> sort(String field, SortOrder order);
    default Query<T> sort(String field) {
        return sort(field, SortOrder.ASC);
    }

    default List<T> get() {
        return getAsync().get();
    }
    BonsaiFuture<List<T>> getAsync();

    default void set(String field, Object value) {
        setAsync(field, value);
    }
    BonsaiFuture<Void> setAsync(String field, Object value);

    default void set(Map<String, Object> updates) {
        setAsync(updates);
    }
    BonsaiFuture<Void> setAsync(Map<String, Object> updates);

    default Integer count() {
        return countAsync().get();
    }
    BonsaiFuture<Integer> countAsync();

    default void  delete() {
        deleteAsync();
    }
    BonsaiFuture<Void> deleteAsync();

    default boolean exists() {
        return existsAsync().get();
    }
    default BonsaiFuture<Boolean> existsAsync() {
        return countAsync().map(c -> c > 0);
    }
}