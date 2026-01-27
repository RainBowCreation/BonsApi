package net.rainbowcreation.bonsai.api;

import java.nio.ByteBuffer;

public class BonsaiResponse {
    public long id;
    public int status; // 200, 404, 500
    public byte[] body;

    public BonsaiResponse(long id, int status, byte[] body) {
        this.id = id;
        this.status = status;
        this.body = body;
    }

    // --- Serialization ---
    public byte[] getBytes() {
        int bodyLen = (body == null) ? 0 : body.length;
        // Size: ID(8) + Status(4) + BodyLen(4) + Body
        ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + 4 + bodyLen);
        buffer.putLong(id);
        buffer.putInt(status);
        buffer.putInt(bodyLen);
        if (bodyLen > 0) buffer.put(body);
        return buffer.array();
    }

    // --- Deserialization ---
    public static BonsaiResponse fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long id = buffer.getLong();
        int status = buffer.getInt();
        int bodyLen = buffer.getInt();

        byte[] body = null;
        if (bodyLen > 0) {
            body = new byte[bodyLen];
            buffer.get(body);
        }
        return new BonsaiResponse(id, status, body);
    }

    public BonsaiResponse(int status, byte[] body) {
        this.status = status;
        this.body = body;
    }

    public static BonsaiResponse ok() {
        return new BonsaiResponse(200, null);
    }

    public static BonsaiResponse ok (byte[] body) {
        return new BonsaiResponse(200, body);
    }

    public static BonsaiResponse error(int code, String msg) {
        return new BonsaiResponse(code, msg.getBytes());
    }
}