package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.ChangeEvent;
import net.rainbowcreation.bonsai.api.connection.ChangeEventRouter;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.connection.RequestOp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteRootTest {

    private Connection stubConn(AtomicReference<ChangeEventRouter> routerRef) {
        return new Connection() {
            @Override
            public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
                return CompletableFuture.completedFuture(new byte[0]);
            }
            @Override
            public void setChangeEventRouter(ChangeEventRouter router) {
                if (routerRef != null) routerRef.set(router);
            }
            @Override
            public void stop() {}
        };
    }

    /** RED-1 → GREEN-1: router must be set on connection at construction time. */
    @Test
    void routerAttachedToConnectionAtConstruction() {
        AtomicReference<ChangeEventRouter> captured = new AtomicReference<>();
        new RemoteRoot(stubConn(captured), "db", null);
        assertNotNull(captured.get());
    }

    /**
     * RED-2 → GREEN-2: every table passed to createCachedTable must be
     * reachable via the router by dbId/tableId.
     * createCachedTable is package-private so this test can call it directly.
     */
    @Test
    void cachedTableBoundToRouter() {
        AtomicReference<ChangeEventRouter> captured = new AtomicReference<>();
        Connection conn = stubConn(captured);
        RemoteRoot root = new RemoteRoot(conn, "db", null);

        List<ChangeEvent> received = new ArrayList<>();
        RemoteTable<Object> spy = new RemoteTable<Object>(conn, (short) 1, (short) 2, "db", "T", Object.class, true) {
            @Override
            public void applyChangeEvent(ChangeEvent e) { received.add(e); }
        };

        root.createCachedTable(spy, "T");

        ChangeEvent event = new ChangeEvent(0L, 0L, (short) 1, (short) 2, "db", "T", "k", new byte[]{1}, false);
        captured.get().accept(event);

        assertEquals(1, received.size());
        assertSame(event, received.get(0));
    }
}
