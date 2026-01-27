package net.rainbowcreation.bonsai.api;

import java.nio.ByteBuffer;

public class BonsaiResponse {
    public final long id;
    public final int status;
    public final byte[] body;

    public BonsaiResponse(long id, int status, byte[] body) {
        this.id = id;
        this.status = status;
        this.body = body;
    }

    public static BonsaiResponse ok() { return new BonsaiResponse(0, 200, null); }
    public static BonsaiResponse ok(byte[] body) { return new BonsaiResponse(0, 200, body); }
    public static BonsaiResponse error(int status, String msg) { return new BonsaiResponse(0, status, msg.getBytes()); }

    public byte[] getBytes() {
        int bodyLen = (body == null) ? 0 : body.length;
        ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + 4 + bodyLen);
        buffer.putLong(id);
        buffer.putInt(status);
        buffer.putInt(bodyLen);
        if (bodyLen > 0) buffer.put(body);
        return buffer.array();
    }

    public static BonsaiResponse fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long id = buffer.getLong();
        int status = buffer.getInt();
        int len = buffer.getInt();
        byte[] body = null;
        if (len > 0) {
            body = new byte[len];
            buffer.get(body);
        }
        return new BonsaiResponse(id, status, body);
    }
}