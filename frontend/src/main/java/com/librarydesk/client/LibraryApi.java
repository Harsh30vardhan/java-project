package com.librarydesk.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class LibraryApi {
    public record Book(long id, String isbn, String title, String author, String genre,
                       int totalCopies, int availableCopies) {}

    public record BookInput(String isbn, String title, String author, String genre, int totalCopies) {}

    public record Member(long id, String name, String email) {}

    public record MemberInput(String name, String email) {}

    public enum LoanStatus {
        OPEN("Open"), OVERDUE("Overdue"), RETURNED("Returned");

        private final String label;

        LoanStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Loan(long id, long bookId, String bookTitle, long memberId, String memberName,
                       String checkedOutOn, String dueOn, String returnedOn, LoanStatus status) {}

    public record CheckoutInput(long bookId, long memberId) {}

    public record Stats(int titles, int members, int openLoans, int overdueLoans) {}

    static final class ApiException extends Exception {
        ApiException(String message, Throwable cause) {
            super(message, cause);
        }

        ApiException(String message) {
            super(message);
        }
    }

    private final String baseUrl;
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    LibraryApi(String baseUrl) {
        this.baseUrl = baseUrl.strip().replaceFirst("/+$", "");
    }

    Stats stats() throws Exception {
        return request("GET", "/stats", null, new TypeReference<Stats>() {});
    }

    List<Book> books(String search) throws Exception {
        return request("GET", "/books" + searchQuery(search), null, new TypeReference<List<Book>>() {});
    }

    Book saveBook(Book existing, BookInput book) throws Exception {
        return existing == null
                ? request("POST", "/books", book, new TypeReference<Book>() {})
                : request("PUT", "/books/" + existing.id(), book, new TypeReference<Book>() {});
    }

    List<Member> members(String search) throws Exception {
        return request("GET", "/members" + searchQuery(search), null, new TypeReference<List<Member>>() {});
    }

    Member saveMember(Member existing, MemberInput member) throws Exception {
        return existing == null
                ? request("POST", "/members", member, new TypeReference<Member>() {})
                : request("PUT", "/members/" + existing.id(), member, new TypeReference<Member>() {});
    }

    List<Loan> loans(LoanStatus status) throws Exception {
        String path = status == null ? "/loans" : "/loans?status=" + status.name();
        return request("GET", path, null, new TypeReference<List<Loan>>() {});
    }

    Loan checkout(CheckoutInput input) throws Exception {
        return request("POST", "/loans", input, new TypeReference<Loan>() {});
    }

    Loan returnLoan(long id) throws Exception {
        return request("POST", "/loans/" + id + "/return", null, new TypeReference<Loan>() {});
    }

    private static String searchQuery(String search) {
        if (search.isBlank()) {
            return "";
        }
        return "?q=" + URLEncoder.encode(search.strip(), StandardCharsets.UTF_8);
    }

    private <T> T request(String method, String route, Object body, TypeReference<T> type)
            throws ApiException, InterruptedException {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + route))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json");
        } catch (IllegalArgumentException exception) {
            throw new ApiException("Set LIBRARY_API_URL to a valid HTTP or HTTPS API address.", exception);
        }

        try {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
        } catch (JsonProcessingException exception) {
            throw new ApiException("The request could not be prepared.", exception);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            throw new ApiException("The library server timed out. Try again.", exception);
        } catch (IOException exception) {
            throw new ApiException("Could not connect to the library server. Check that it is running.", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(errorMessage(response));
        }
        try {
            return json.readValue(response.body(), type);
        } catch (JsonProcessingException exception) {
            throw new ApiException("The library server sent an unreadable response.", exception);
        }
    }

    private String errorMessage(HttpResponse<String> response) {
        try {
            String message = json.readTree(response.body()).path("message").asText();
            if (!message.isBlank()) {
                return message;
            }
        } catch (JsonProcessingException ignored) {
            // The status code is still available when the server cannot provide a JSON error.
        }
        return "The library server returned HTTP " + response.statusCode() + ".";
    }

    void close() {
        http.shutdownNow();
    }
}
