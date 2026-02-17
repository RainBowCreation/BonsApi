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
        this.db = (db == null) ? "" : db;
        this.table = (table == null) ? "" : table;
        this.key = (key == null) ? "" : key;
        this.payload = payload;
    }

    // --- Serialization ---
    public byte[] getBytes() {
        byte[] dbBytes = db.getBytes(StandardCharsets.UTF_8);
        byte[] tableBytes = table.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        checkStringLimit("db", dbBytes);
        checkStringLimit("table", tableBytes);
        checkStringLimit("key", keyBytes);

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

    private void checkStringLimit(String field, byte[] bytes) {
        if (bytes.length > 32767) {
            throw new IllegalArgumentException("Field '" + field + "' exceeds max length of 32767 bytes (Actual: " + bytes.length + ")");
        }
    }

    private void writeString(ByteBuffer buf, byte[] strBytes) {
        buf.putShort((short) strBytes.length);
        buf.put(strBytes);
    }

    // --- Deserialization ---
    public static BonsaiRequest fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        try {
            long id = buffer.getLong();
            byte opByte = buffer.get();
            RequestOp op = RequestOp.fromId(opByte);
            if (op == null) op = RequestOp.GET; // Fallback

            String db = readString(buffer);
            String table = readString(buffer);
            String key = readString(buffer);

            int pLen = buffer.getInt();
            byte[] payload = null;
            if (pLen > 0) {
                if (buffer.remaining() < pLen) {
                    throw new IllegalStateException("Payload underflow: Expected " + pLen + ", available " + buffer.remaining());
                }
                payload = new byte[pLen];
                buffer.get(payload);
            }

            BonsaiRequest req = new BonsaiRequest(op, db, table, key, payload);
            req.id = id;
            return req;
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed: " + e.getMessage() + " [DataLen=" + data.length + "]");
        }
    }

    private static String readString(ByteBuffer buf) {
        short len = buf.getShort();
        if (len < 0) throw new IllegalStateException("Negative string length: " + len);
        if (len == 0) return "";

        if (buf.remaining() < len) {
            throw new IllegalStateException("String underflow: Expected " + len + ", available " + buf.remaining());
        }

        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}