package com.librarydesk.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

import static com.librarydesk.server.LibraryRecords.*;

final class LibraryApi implements HttpHandler {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern BOOK_PATH = Pattern.compile("/api/books/([^/]+)");
    private static final Pattern MEMBER_PATH = Pattern.compile("/api/members/([^/]+)");
    private static final Pattern RETURN_PATH = Pattern.compile("/api/loans/([^/]+)/return");
    private static final Pattern ID = Pattern.compile("[0-9]+");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s.]+(?:\\.[^@\\s.]+)+");
    private static final System.Logger LOGGER = System.getLogger(LibraryApi.class.getName());

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final LibraryStore store;

    LibraryApi(LibraryStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = route(exchange);
            } catch (ApiException error) {
                response = new Response(error.status(), Map.of("message", error.getMessage()));
            } catch (SQLException | RuntimeException error) {
                LOGGER.log(System.Logger.Level.ERROR, "Unable to complete a library request.", error);
                response = new Response(500, Map.of("message", "An unexpected server error occurred."));
            }

            byte[] body = json.writeValueAsBytes(response.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        }
    }

    private Response route(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        return switch (path) {
            case "/api/health" -> {
                requireMethod(exchange, "GET");
                yield ok(Map.of("status", "ok"));
            }
            case "/api/stats" -> {
                requireMethod(exchange, "GET");
                yield ok(store.stats());
            }
            case "/api/books" -> switch (method) {
                case "GET" -> ok(store.books(searchParameter(exchange)));
                case "POST" -> new Response(201, store.createBook(bookInput(readBody(exchange))));
                default -> throw methodNotAllowed(exchange, "GET, POST");
            };
            case "/api/members" -> switch (method) {
                case "GET" -> ok(store.members(searchParameter(exchange)));
                case "POST" -> new Response(201, store.createMember(memberInput(readBody(exchange))));
                default -> throw methodNotAllowed(exchange, "GET, POST");
            };
            case "/api/loans" -> switch (method) {
                case "GET" -> ok(store.loans(statusParameter(exchange)));
                case "POST" -> {
                    JsonNode input = readBody(exchange);
                    yield new Response(201, store.checkout(integer(input, "bookId", 1, Long.MAX_VALUE),
                            integer(input, "memberId", 1, Long.MAX_VALUE)));
                }
                default -> throw methodNotAllowed(exchange, "GET, POST");
            };
            default -> itemRoute(exchange, path);
        };
    }

    private Response itemRoute(HttpExchange exchange, String path) throws IOException, SQLException {
        var book = BOOK_PATH.matcher(path);
        if (book.matches()) {
            requireMethod(exchange, "PUT");
            return ok(store.updateBook(parseId(book.group(1)), bookInput(readBody(exchange))));
        }
        var member = MEMBER_PATH.matcher(path);
        if (member.matches()) {
            requireMethod(exchange, "PUT");
            return ok(store.updateMember(parseId(member.group(1)), memberInput(readBody(exchange))));
        }
        var loan = RETURN_PATH.matcher(path);
        if (loan.matches()) {
            requireMethod(exchange, "POST");
            return ok(store.returnLoan(parseId(loan.group(1))));
        }
        throw new ApiException(404, "Endpoint not found.");
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(400, "Request body is too large.");
        }
        JsonNode body;
        try {
            body = json.readTree(bytes);
        } catch (IOException error) {
            throw new ApiException(400, "Request body must be valid JSON.");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Request body must be a JSON object.");
        }
        return body;
    }

    private static BookInput bookInput(JsonNode input) {
        return new BookInput(text(input, "isbn", 32, true), text(input, "title", 200, true),
                text(input, "author", 160, true), text(input, "genre", 80, false),
                (int) integer(input, "totalCopies", 1, 999));
    }

    private static MemberInput memberInput(JsonNode input) {
        String name = text(input, "name", 120, true);
        String email = text(input, "email", 254, true);
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(400, "Enter a valid email address.");
        }
        return new MemberInput(name, email);
    }

    private static String text(JsonNode input, String field, int max, boolean required) {
        JsonNode value = input.get(field);
        if (value == null && !required) {
            return "";
        }
        if (value == null || !value.isTextual()) {
            throw new ApiException(400, field + " must be text.");
        }

        String raw = value.textValue();
        String text = raw.strip();
        if (required && text.isEmpty()) {
            throw new ApiException(400, field + " is required.");
        }
        if (raw.codePointCount(0, raw.length()) > max) {
            throw new ApiException(400, field + " must be " + max + " characters or fewer.");
        }
        return text;
    }

    private static long integer(JsonNode input, String field, long min, long max) {
        JsonNode value = input.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) {
            throw new ApiException(400, max == Long.MAX_VALUE
                    ? field + " must be a positive integer."
                    : field + " must be an integer from " + min + " to " + max + ".");
        }
        return value.longValue();
    }

    private static long parseId(String value) {
        if (ID.matcher(value).matches()) {
            try {
                long id = Long.parseLong(value);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // IDs must fit in a signed 64-bit integer.
            }
        }
        throw new ApiException(400, "ID must be a positive integer.");
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        try {
            for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", 2);
                if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                    return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                }
            }
        } catch (IllegalArgumentException error) {
            throw new ApiException(400, "Query parameters must be URL encoded.");
        }
        return null;
    }

    private static String searchParameter(HttpExchange exchange) {
        String value = queryParameter(exchange, "q");
        return value == null ? "" : value;
    }

    private static LoanStatus statusParameter(HttpExchange exchange) {
        String value = queryParameter(exchange, "status");
        if (value == null) {
            return null;
        }
        try {
            return LoanStatus.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new ApiException(400, "Status must be OPEN, OVERDUE, or RETURNED.");
        }
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equals(exchange.getRequestMethod())) {
            throw methodNotAllowed(exchange, expected);
        }
    }

    private static ApiException methodNotAllowed(HttpExchange exchange, String allowed) {
        exchange.getResponseHeaders().set("Allow", allowed);
        return new ApiException(405, "This method is not supported for the endpoint.");
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private record Response(int status, Object body) {}
}
