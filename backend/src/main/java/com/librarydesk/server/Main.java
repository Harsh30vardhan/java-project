package com.librarydesk.server;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("LIBRARY_PORT", "8080"));
        Path database = Path.of(System.getenv().getOrDefault("LIBRARY_DB", "data/library.db")).toAbsolutePath();
        Files.createDirectories(database.getParent());

        LibraryStore store = new LibraryStore(database, Clock.systemDefaultZone());
        store.initialize();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api", new LibraryApi(store));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.shutdown();
        }, "library-server-shutdown"));
        server.start();

        System.out.printf("Library API listening at http://127.0.0.1:%d/api%n", server.getAddress().getPort());
        System.out.printf("SQLite database: %s%n", database);
    }
}
