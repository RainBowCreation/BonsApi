package net.rainbowcreation.bonsai.api;

import net.rainbowcreation.bonsai.api.impl.RemoteBonsai;
import net.rainbowcreation.bonsai.api.internal.HttpConnection;
import net.rainbowcreation.bonsai.api.internal.TcpConnection;
import net.rainbowcreation.bonsai.api.util.Stoppable;

import java.net.Socket;

import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class BonsApi implements Stoppable {

    // --- Configuration ---
    public static String HOST = "127.0.0.1";
    public static int TCP_PORT = 7071;
    public static int HTTP_PORT = 7070;

    public static final ExecutorService WORKER_POOL = Executors.newCachedThreadPool();
    public static Logger LOGGER = Logger.getLogger(BonsApi.class.getName());

    // Singleton
    private static volatile Bonsai INSTANCE;

    public static Bonsai getBonsai() {
        if (INSTANCE != null) return INSTANCE;
        synchronized (BonsApi.class) {
            if (INSTANCE != null) return INSTANCE;
            INSTANCE = init();
        }
        return INSTANCE;
    }

    public static void setBonsai(Bonsai bonsai) {
        INSTANCE = bonsai;
    }

    public static void shutdown() {
        if (INSTANCE != null) INSTANCE.stop();
        WORKER_POOL.shutdown();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private static Bonsai init() {
        // Direct Core
        try {
            for (Bonsai core : ServiceLoader.load(Bonsai.class)) {
                LOGGER.info("Using Direct Core.");
                return core;
            }
        } catch (Throwable ignored) {}

        // TCP
        try (Socket s = new Socket(HOST, TCP_PORT)) {
            LOGGER.info("Connected via TCP.");
            return new RemoteBonsai(new TcpConnection(HOST, TCP_PORT));
        } catch (Exception ignored) {}

        // HTTP
        try {
            LOGGER.info("Falling back to HTTP.");
            return new RemoteBonsai(new HttpConnection(HOST, HTTP_PORT));
        } catch (Exception e) {
            throw new RuntimeException(" Fatal: No connection method found. Is Bonsai running?", e);
        }
    }
}