package net.rainbowcreation.bonsai.api.util;

import net.rainbowcreation.bonsai.api.BonsaiRequest;
import net.rainbowcreation.bonsai.api.BonsaiResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class SerdeUtil {
    public static byte[] serializeRequest(BonsaiRequest req) {
        byte[] op = getBytes(req.op);
        byte[] db = getBytes(req.db);
        byte[] table = getBytes(req.table);
        byte[] key = getBytes(req.key);
        byte[] payload = req.payload;

        int size = 8 + // id
                4 + op.length +
                4 + db.length +
                4 + table.length +
                4 + key.length +
                4 + (payload == null ? 0 : payload.length);

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putLong(req.id);
        writeBytes(buf, op);
        writeBytes(buf, db);
        writeBytes(buf, table);
        writeBytes(buf, key);
        writeBytes(buf, payload);

        return buf.array();
    }

    public static BonsaiRequest deserializeRequest(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        long id = buf.getLong();
        String op = readString(buf);
        String db = readString(buf);
        String table = readString(buf);
        String key = readString(buf);
        byte[] payload = readBytes(buf);

        BonsaiRequest req = new BonsaiRequest(op, db, table, key, payload);
        req.id = id;
        return req;
    }

    // --- Response Serialization ---

    public static byte[] serializeResponse(BonsaiResponse res) {
        byte[] body = res.body;
        int size = 8 + // id
                4 + // status
                4 + (body == null ? 0 : body.length);

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putLong(res.id);
        buf.putInt(res.status);
        writeBytes(buf, body);

        return buf.array();
    }

    public static BonsaiResponse deserializeResponse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        long id = buf.getLong();
        int status = buf.getInt();
        byte[] body = readBytes(buf);

        BonsaiResponse res = new BonsaiResponse(status, body);
        res.id = id;
        return res;
    }

    private static byte[] getBytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeBytes(ByteBuffer buf, byte[] b) {
        if (b == null) {
            buf.putInt(-1);
        } else {
            buf.putInt(b.length);
            buf.put(b);
        }
    }

    private static byte[] readBytes(ByteBuffer buf) {
        int len = buf.getInt();
        if (len == -1) return null;
        byte[] b = new byte[len];
        buf.get(b);
        return b;
    }

    private static String readString(ByteBuffer buf) {
        byte[] b = readBytes(buf);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}
