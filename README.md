# Library Management System

A JavaFX desktop app for managing a library catalog, registering members, checking books out, and recording returns. The Java REST API uses JDBC to store records in SQLite. A new database includes sample records for exploring the app.

## Run locally

Use JDK 21 or newer, Maven, and a graphical desktop. On macOS or Linux, run this from the project directory.

```sh
./run-local.sh
```

The launcher builds both modules, starts the API on `127.0.0.1:8080`, and opens the JavaFX window. Closing the window also stops the API. Data is stored in `data/library.db` by default.

To run the two processes independently, build with `mvn package` and use two terminals.

```sh
LIBRARY_DB="$PWD/data/library.db" mvn -pl backend exec:java
```

```sh
mvn -pl frontend javafx:run
```

Set `LIBRARY_PORT` to change the server port, `LIBRARY_DB` for another SQLite file, or `LIBRARY_API_URL` for a different client endpoint.

## Try a checkout

1. Open **Catalog** to browse titles and see how many copies are available.
2. Open **Members** to browse or register a borrower.
3. In **Circulation**, choose an available title and a member to check it out. The app sets the due date 14 days ahead.
4. Select an open loan in **Circulation** and return it. Its history stays visible, and the copy becomes available again.

[System architecture](docs/architecture.md) · [OpenAPI specification](docs/openapi.json)
