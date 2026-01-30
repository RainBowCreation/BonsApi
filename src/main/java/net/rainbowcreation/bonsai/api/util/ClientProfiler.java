package net.rainbowcreation.bonsai.api.util;

import net.rainbowcreation.bonsai.api.config.ClientConfig;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Client-side profiler to measure request latency breakdown.
 *
 * Enable via: -Dbonsai.client.profiler.enabled=true
 * Output file: -Dbonsai.client.profiler.output=client-profile.log
 * Sample rate: -Dbonsai.client.profiler.sampleRate=100 (1 in N)
 */
public class ClientProfiler {

    public static final boolean ENABLED = ClientConfig.PROFILER_ENABLED;
    private static final String OUTPUT_FILE = ClientConfig.PROFILER_OUTPUT_FILE;
    private static final int SAMPLE_RATE = ClientConfig.PROFILER_SAMPLE_RATE;

    private static final Map<Long, RequestTiming> timings = new ConcurrentHashMap<>();
    private static final AtomicLong sampleCounter = new AtomicLong(0);

    private static final LongAdder totalRequests = new LongAdder();
    private static final LongAdder bufferWaitSum = new LongAdder();
    private static final LongAdder networkTimeSum = new LongAdder();
    private static final LongAdder totalTimeSum = new LongAdder();

    // Histogram buckets (in ms): <1, <2, <5, <10, <20, <50, <100, >=100
    private static final int[] BUCKETS = {1, 2, 5, 10, 20, 50, 100, Integer.MAX_VALUE};
    private static final LongAdder[] totalTimeBuckets = new LongAdder[BUCKETS.length];

    private static PrintWriter logWriter;

    static {
        for (int i = 0; i < BUCKETS.length; i++) {
            totalTimeBuckets[i] = new LongAdder();
        }

        if (ENABLED) {
            try {
                logWriter = new PrintWriter(new FileWriter(OUTPUT_FILE, true), true);
                logWriter.println("# Client Profiler Started");
                logWriter.println("# Format: reqId|sendNs|flushNs|responseNs|bufferWaitMs|networkMs|totalMs");
                System.out.println("[CLIENT_PROFILER] Enabled. Output: " + OUTPUT_FILE + ", Sample rate: 1/" + SAMPLE_RATE);
            } catch (Exception e) {
                System.err.println("[CLIENT_PROFILER] Failed to open log: " + e.getMessage());
            }

            Runtime.getRuntime().addShutdownHook(new Thread(ClientProfiler::printStats));
        }
    }

    private static class RequestTiming {
        volatile long sendNs;
        volatile long flushNs;
        volatile long responseNs;
    }

    /**
     * Called when request is written to buffer.
     */
    public static void onSend(long reqId) {
        if (!ENABLED) return;
        if (SAMPLE_RATE > 1 && (sampleCounter.incrementAndGet() % SAMPLE_RATE) != 0) return;

        RequestTiming t = new RequestTiming();
        t.sendNs = System.nanoTime();
        timings.put(reqId, t);
    }

    /**
     * Called when buffer is flushed to network.
     */
    public static void onFlush(long reqId) {
        if (!ENABLED) return;
        RequestTiming t = timings.get(reqId);
        if (t != null && t.flushNs == 0) {
            t.flushNs = System.nanoTime();
        }
    }

    /**
     * Called for all pending requests when flush happens.
     */
    public static void onFlushAll(Iterable<Long> reqIds) {
        if (!ENABLED) return;
        long now = System.nanoTime();
        for (Long reqId : reqIds) {
            RequestTiming t = timings.get(reqId);
            if (t != null && t.flushNs == 0) {
                t.flushNs = now;
            }
        }
    }

    /**
     * Called when response is received.
     */
    public static void onResponse(long reqId) {
        if (!ENABLED) return;
        RequestTiming t = timings.remove(reqId);
        if (t == null) return;

        t.responseNs = System.nanoTime();

        long bufferWaitNs = (t.flushNs > 0) ? (t.flushNs - t.sendNs) : 0;
        long networkNs = (t.flushNs > 0) ? (t.responseNs - t.flushNs) : (t.responseNs - t.sendNs);
        long totalNs = t.responseNs - t.sendNs;

        double bufferWaitMs = bufferWaitNs / 1_000_000.0;
        double networkMs = networkNs / 1_000_000.0;
        double totalMs = totalNs / 1_000_000.0;

        totalRequests.increment();
        bufferWaitSum.add(bufferWaitNs / 1_000);
        networkTimeSum.add(networkNs / 1_000);
        totalTimeSum.add(totalNs / 1_000);

        int totalMsInt = (int) totalMs;
        for (int i = 0; i < BUCKETS.length; i++) {
            if (totalMsInt < BUCKETS[i]) {
                totalTimeBuckets[i].increment();
                break;
            }
        }

        if (logWriter != null) {
            logWriter.printf("%d|%d|%d|%d|%.3f|%.3f|%.3f%n",
                    reqId, t.sendNs, t.flushNs, t.responseNs,
                    bufferWaitMs, networkMs, totalMs);
        }
    }

    public static void printStats() {
        long count = totalRequests.sum();
        if (count == 0) {
            System.out.println("[CLIENT_PROFILER] No requests recorded");
            return;
        }

        double avgBufferMs = (bufferWaitSum.sum() / 1000.0) / count;
        double avgNetworkMs = (networkTimeSum.sum() / 1000.0) / count;
        double avgTotalMs = (totalTimeSum.sum() / 1000.0) / count;

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           CLIENT-SIDE LATENCY BREAKDOWN                      ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Total Requests:    %,15d                         ║%n", count);
        System.out.printf("║  Avg Buffer Wait:   %15.3f ms                      ║%n", avgBufferMs);
        System.out.printf("║  Avg Network Time:  %15.3f ms                      ║%n", avgNetworkMs);
        System.out.printf("║  Avg Total Time:    %15.3f ms                      ║%n", avgTotalMs);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Latency Distribution:                                       ║");

        String[] labels = {"<1ms", "<2ms", "<5ms", "<10ms", "<20ms", "<50ms", "<100ms", ">=100ms"};
        for (int i = 0; i < BUCKETS.length; i++) {
            long bucketCount = totalTimeBuckets[i].sum();
            double pct = (bucketCount * 100.0) / count;
            if (bucketCount > 0) {
                System.out.printf("║    %7s: %,10d (%5.1f%%)                            ║%n",
                        labels[i], bucketCount, pct);
            }
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        if (logWriter != null) {
            logWriter.println("# Stats: count=" + count + " avgBuffer=" + avgBufferMs +
                    "ms avgNetwork=" + avgNetworkMs + "ms avgTotal=" + avgTotalMs + "ms");
            logWriter.close();
        }
    }
}
