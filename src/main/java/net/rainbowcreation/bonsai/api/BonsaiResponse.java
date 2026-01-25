package net.rainbowcreation.bonsai.api;

public class BonsaiResponse {
    public long id;
    public int status;
    public byte[] body;

    public BonsaiResponse(int status, byte[] body) {
        this.status = status;
        this.body = body;
    }

    public static BonsaiResponse ok() {
        return new BonsaiResponse(200, null);
    }

    public static BonsaiResponse ok(byte[] body) {
        return new BonsaiResponse(200, body);
    }

    public static BonsaiResponse error(int code, String msg) {
        return new BonsaiResponse(code, msg.getBytes());
    }
}