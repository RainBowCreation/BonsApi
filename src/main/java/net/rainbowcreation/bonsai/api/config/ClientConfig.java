package net.rainbowcreation.bonsai.api.config;

/**
 * Client-side configuration constants for BonsApi.
 * All values can be overridden via system properties (-Dbonsai.xxx=value).
 */
public class ClientConfig {

    /** Number of connections in the pool. Default: 4 */
    public static final int POOL_SIZE =
            Integer.getInteger("bonsai.pool.size", 4);

    /** Maximum pending requests per connection (pipelining). Default: 100 */
    public static final int PIPELINE_MAX_PENDING =
            Integer.getInteger("bonsai.pipeline.max", 100);

    /** Flush threshold for write buffer (bytes). Default: 64KB */
    public static final int WRITE_FLUSH_THRESHOLD =
            Integer.getInteger("bonsai.write.flushThreshold", 65536);

    /** Auto-flush interval for write buffer (ms). Default: 1ms */
    public static final int WRITE_FLUSH_INTERVAL_MS =
            Integer.getInteger("bonsai.write.flushInterval", 1);

    /** Socket send buffer size (bytes). Default: 128KB */
    public static final int SOCKET_SEND_BUFFER =
            Integer.getInteger("bonsai.socket.sendBuffer", 131072);

    /** Socket receive buffer size (bytes). Default: 128KB */
    public static final int SOCKET_RECEIVE_BUFFER =
            Integer.getInteger("bonsai.socket.receiveBuffer", 131072);
}
