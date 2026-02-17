package net.rainbowcreation.bonsai.api.config;

public class Config {

    public static final int POOL_SIZE =
            Integer.getInteger("bonsai.pool.size", 4);

    public static final int PIPELINE_MAX_PENDING =
            Integer.getInteger("bonsai.pipeline.max", 100);

    public static final int WRITE_FLUSH_THRESHOLD =
            Integer.getInteger("bonsai.write.flushThreshold", 65536);

    public static final int WRITE_FLUSH_INTERVAL_MS =
            Integer.getInteger("bonsai.write.flushInterval", 1);

    public static final int SOCKET_SEND_BUFFER =
            Integer.getInteger("bonsai.socket.sendBuffer", 131072);

    public static final int SOCKET_RECEIVE_BUFFER =
            Integer.getInteger("bonsai.socket.receiveBuffer", 131072);

    public static final boolean CACHE_ENABLED =
            Boolean.getBoolean("bonsai.cache.enabled");

    public static final long CACHE_MAX_SIZE =
            Long.getLong("bonsai.cache.maxSize", 5000L);

    public static final int CACHE_TTL_SECONDS =
            Integer.getInteger("bonsai.cache.ttl", 60);

    public static final boolean CACHE_STATS_ENABLED =
            Boolean.getBoolean("bonsai.cache.stats");

    public static final boolean PROFILER_ENABLED = Boolean.getBoolean("bonsai.profiler.enabled");
    public static final String PROFILER_OUTPUT_FILE = System.getProperty("bonsai.profiler.output", "client-profile.log");
    public static final int PROFILER_SAMPLE_RATE = Integer.getInteger("bonsai.profiler.sampleRate", 1);
}
