package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.ChangeEvent;
import net.rainbowcreation.bonsai.api.impl.RemoteTable;
import net.rainbowcreation.bonsai.connection.RequestOp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ChangeEventRouterTest {

    private Connection fakeConn() {
        return new Connection() {
            @Override
            public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
                return new CompletableFuture<>();
            }
            @Override
            public void stop() {}
        };
    }

    @Test
    void bindAndAcceptDelivers() {
        ChangeEventRouter router = new ChangeEventRouter();
        List<ChangeEvent> seen = new ArrayList<>();
        RemoteTable<String> t = new RemoteTable<String>(fakeConn(), "db", "T", String.class) {
            @Override
            public void applyChangeEvent(ChangeEvent e) { seen.add(e); }
        };
        router.bind((short) 1, (short) 2, t);
        ChangeEvent event = new ChangeEvent(0L, 0L, (short) 1, (short) 2, "db", "T", "k", new byte[]{1}, false);
        router.accept(event);
        assertEquals(1, seen.size());
        assertSame(event, seen.get(0));
    }

    @Test
    void acceptUnknownKeyIsNoOp() {
        ChangeEventRouter router = new ChangeEventRouter();
        ChangeEvent event = new ChangeEvent(0L, 0L, (short) 9, (short) 9, "db", "T", "k", new byte[]{1}, false);
        assertDoesNotThrow(() -> router.accept(event));
    }

    @Test
    void hasSourceFalseUntilRegistered() {
        ChangeEventRouter router = new ChangeEventRouter();
        assertFalse(router.hasSource());
        Object src = new Object();
        router.registerSource(src);
        assertTrue(router.hasSource());
        router.unregisterSource(src);
        assertFalse(router.hasSource());
    }

    @Test
    void perSourceThreadArrivalOrderPreserved() {
        ChangeEventRouter router = new ChangeEventRouter();
        List<ChangeEvent> seen = new ArrayList<>();
        RemoteTable<String> t = new RemoteTable<String>(fakeConn(), "db", "T", String.class) {
            @Override
            public void applyChangeEvent(ChangeEvent e) { seen.add(e); }
        };
        router.bind((short) 1, (short) 1, t);

        int n = 100;
        for (int i = 0; i < n; i++) {
            ChangeEvent e = new ChangeEvent((long) i, 0L, (short) 1, (short) 1, "db", "T", "k" + i, new byte[]{1}, false);
            router.accept(e);
        }

        assertEquals(n, seen.size());
        for (int i = 0; i < n; i++) {
            assertEquals((long) i, seen.get(i).globalSeq);
        }
    }
}
