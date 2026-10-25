package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.ChangeEvent;
import net.rainbowcreation.bonsai.api.impl.RemoteTable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChangeEventRouter {

    private final ConcurrentHashMap<Long, RemoteTable<?>> routes = new ConcurrentHashMap<>();
    private final Set<Object> sources = ConcurrentHashMap.newKeySet();

    private static long compositeKey(short dbId, short tableId) {
        return ((long) (dbId & 0xFFFF) << 16) | (tableId & 0xFFFF);
    }

    public void bind(short dbId, short tableId, RemoteTable<?> table) {
        routes.put(compositeKey(dbId, tableId), table);
    }

    public void unbind(short dbId, short tableId) {
        routes.remove(compositeKey(dbId, tableId));
    }

    public void accept(ChangeEvent event) {
        RemoteTable<?> table = routes.get(compositeKey(event.dbId, event.tableId));
        if (table != null) {
            table.applyChangeEvent(event);
        }
    }

    public boolean hasSource() {
        return !sources.isEmpty();
    }

    public void registerSource(Object source) {
        sources.add(source);
    }

    public void unregisterSource(Object source) {
        sources.remove(source);
    }
}
