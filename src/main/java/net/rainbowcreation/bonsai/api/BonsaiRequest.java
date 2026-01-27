package net.rainbowcreation.bonsai.api;

import net.rainbowcreation.bonsai.api.connection.RequestOp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class BonsaiRequest {
    public long id;
    public final RequestOp op;
    public final String db;
    public final String table;
    public final String key;
    public final byte[] payload;

    public BonsaiRequest(RequestOp op, String db, String table, String key, byte[] payload) {
        this.op = op;
        this.db = db;
        this.table = table;
        this.key = key;
        this.payload = payload;
    }

    // --- Serialization ---
    public byte[] getBytes() {
        byte[] dbBytes = db.getBytes(StandardCharsets.UTF_8);
        byte[] tableBytes = table.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int payloadLen = (payload == null) ? 0 : payload.length;

        // Calculate size: ID(8) + OP(1) + DB(2+len) + TBL(2+len) + KEY(2+len) + PAYLOAD(4+len)
        int size = 8 + 1 +
                (2 + dbBytes.length) +
                (2 + tableBytes.length) +
                (2 + keyBytes.length) +
                (4 + payloadLen);

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putLong(id);
        buffer.put((byte) op.getId());

        writeString(buffer, dbBytes);
        writeString(buffer, tableBytes);
        writeString(buffer, keyBytes);

        buffer.putInt(payloadLen);
        if (payloadLen > 0) buffer.put(payload);

        return buffer.array();
    }

    private void writeString(ByteBuffer buf, byte[] strBytes) {
        buf.putShort((short) strBytes.length);
        buf.put(strBytes);
    }

    // --- Deserialization ---
    public static BonsaiRequest fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        long id = buffer.getLong();
        RequestOp op = RequestOp.fromId(buffer.get());

        String db = readString(buffer);
        String table = readString(buffer);
        String key = readString(buffer);

        int pLen = buffer.getInt();
        byte[] payload = null;
        if (pLen > 0) {
            payload = new byte[pLen];
            buffer.get(payload);
        }

        BonsaiRequest req = new BonsaiRequest(op, db, table, key, payload);
        req.id = id;
        return req;
    }

    private static String readString(ByteBuffer buf) {
        short len = buf.getShort();
        if (len == 0) return "";
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}