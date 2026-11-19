package com.librarydesk.server;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.librarydesk.server.LibraryRecords.*;

final class LibraryStore {
    private static final String BOOKS = """
            SELECT b.id, b.isbn, b.title, b.author, b.genre, b.total_copies,
                   b.total_copies - (SELECT COUNT(*) FROM loans
                                     WHERE book_id = b.id AND returned_on IS NULL) AS available_copies
            FROM books b
            """;
    private static final String MEMBERS = "SELECT id, name, email FROM members";
    private static final String LOANS = """
            SELECT l.id, l.book_id, b.title AS book_title, l.member_id, m.name AS member_name,
                   l.checked_out_on, l.due_on, l.returned_on,
                   CASE WHEN l.returned_on IS NOT NULL THEN 'RETURNED'
                        WHEN l.due_on < ? THEN 'OVERDUE'
                        ELSE 'OPEN' END AS status
            FROM loans l
            JOIN books b ON b.id = l.book_id
            JOIN members m ON m.id = l.member_id
            """;
    private static final String INSERT_BOOK =
            "INSERT INTO books (isbn, title, author, genre, total_copies) VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_MEMBER = "INSERT INTO members (name, email) VALUES (?, ?)";
    private static final String INSERT_LOAN =
            "INSERT INTO loans (book_id, member_id, checked_out_on, due_on, returned_on) VALUES (?, ?, ?, ?, ?)";

    private final SQLiteDataSource dataSource;
    private final Clock clock;

    LibraryStore(Path database, Clock clock) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        // Acquire the write lock before any reads that determine whether a write is allowed.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + database);
        this.clock = clock;
    }

    void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        }
        write(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS books (
                            id INTEGER PRIMARY KEY,
                            isbn TEXT NOT NULL UNIQUE COLLATE NOCASE,
                            title TEXT NOT NULL,
                            author TEXT NOT NULL,
                            genre TEXT NOT NULL DEFAULT '',
                            total_copies INTEGER NOT NULL CHECK (total_copies BETWEEN 1 AND 999)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS members (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            email TEXT NOT NULL UNIQUE COLLATE NOCASE
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS loans (
                            id INTEGER PRIMARY KEY,
                            book_id INTEGER NOT NULL REFERENCES books(id),
                            member_id INTEGER NOT NULL REFERENCES members(id),
                            checked_out_on TEXT NOT NULL,
                            due_on TEXT NOT NULL CHECK (due_on >= checked_out_on),
                            returned_on TEXT CHECK (returned_on >= checked_out_on)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE UNIQUE INDEX IF NOT EXISTS one_active_loan_per_book_and_member
                        ON loans (book_id, member_id) WHERE returned_on IS NULL
                        """);
            }
            long version = query(connection, "PRAGMA user_version", row -> row.getLong(1)).getFirst();
            if (version == 0) {
                seed(connection);
                update(connection, "PRAGMA user_version = 1");
            }
            return null;
        });
    }

    private void seed(Connection connection) throws SQLException {
        long hobbit = insertBook(connection, new BookInput(
                "9780547928227", "The Hobbit", "J. R. R. Tolkien", "Fantasy", 5));
        long habits = insertBook(connection, new BookInput(
                "9780735211292", "Atomic Habits", "James Clear", "Nonfiction", 3));
        insertBook(connection, new BookInput(
                "9780553380163", "A Brief History of Time", "Stephen Hawking", "Science", 2));
        long mockingbird = insertBook(connection, new BookInput(
                "9780060935467", "To Kill a Mockingbird", "Harper Lee", "Classics", 4));
        insertBook(connection, new BookInput(
                "9780441172719", "Dune", "Frank Herbert", "Science Fiction", 3));
        insertBook(connection, new BookInput(
                "9780141439518", "Pride and Prejudice", "Jane Austen", "Classics", 2));
        insertBook(connection, new BookInput(
                "9780141182575", "One Hundred Years of Solitude", "Gabriel García Márquez", "Fiction", 2));
        insertBook(connection, new BookInput(
                "9780132350884", "Clean Code", "Robert C. Martin", "Technology", 1));

        long anika = insertMember(connection, new MemberInput("Anika Rao", "anika@example.com"));
        long noah = insertMember(connection, new MemberInput("Noah Williams", "noah@example.com"));
        long mira = insertMember(connection, new MemberInput("Mira Patel", "mira@example.com"));
        insertMember(connection, new MemberInput("Oliver Chen", "oliver@example.com"));

        LocalDate today = LocalDate.now(clock);
        insertLoan(connection, hobbit, anika, today.minusDays(4), null);
        insertLoan(connection, habits, noah, today.minusDays(18), null);
        insertLoan(connection, mockingbird, mira, today.minusDays(24), today.minusDays(16));
    }

    Stats stats() throws SQLException {
        String sql = """
                SELECT (SELECT COUNT(*) FROM books) AS titles,
                       (SELECT COUNT(*) FROM members) AS members,
                       (SELECT COUNT(*) FROM loans WHERE returned_on IS NULL) AS open_loans,
                       (SELECT COUNT(*) FROM loans WHERE returned_on IS NULL AND due_on < ?) AS overdue_loans
                """;
        try (Connection connection = dataSource.getConnection()) {
            return query(connection, sql, row -> new Stats(row.getLong("titles"), row.getLong("members"),
                    row.getLong("open_loans"), row.getLong("overdue_loans")), today()).getFirst();
        }
    }

    List<Book> books(String search) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String needle = fold(search);
            return query(connection, BOOKS + " ORDER BY b.title COLLATE NOCASE, b.id", LibraryStore::book)
                    .stream().filter(book -> contains(needle, book.title(), book.author(), book.isbn(), book.genre()))
                    .toList();
        }
    }

    Book createBook(BookInput input) throws SQLException {
        return write(connection -> {
            checkIsbn(connection, input.isbn(), 0);
            return requireBook(connection, insertBook(connection, input));
        });
    }

    Book updateBook(long id, BookInput input) throws SQLException {
        return write(connection -> {
            Book book = requireBook(connection, id);
            checkIsbn(connection, input.isbn(), id);
            if (input.totalCopies() < book.totalCopies() - book.availableCopies()) {
                throw new ApiException(409, "Total copies cannot be lower than the number of copies on loan.");
            }
            update(connection, "UPDATE books SET isbn = ?, title = ?, author = ?, genre = ?, total_copies = ? WHERE id = ?",
                    input.isbn(), input.title(), input.author(), input.genre(), input.totalCopies(), id);
            return requireBook(connection, id);
        });
    }

    List<Member> members(String search) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String needle = fold(search);
            return query(connection, MEMBERS + " ORDER BY name COLLATE NOCASE, id", LibraryStore::member)
                    .stream().filter(member -> contains(needle, member.name(), member.email())).toList();
        }
    }

    Member createMember(MemberInput input) throws SQLException {
        return write(connection -> {
            checkEmail(connection, input.email(), 0);
            return requireMember(connection, insertMember(connection, input));
        });
    }

    Member updateMember(long id, MemberInput input) throws SQLException {
        return write(connection -> {
            requireMember(connection, id);
            checkEmail(connection, input.email(), id);
            update(connection, "UPDATE members SET name = ?, email = ? WHERE id = ?",
                    input.name(), input.email(), id);
            return requireMember(connection, id);
        });
    }

    List<Loan> loans(LoanStatus status) throws SQLException {
        String sql = "SELECT * FROM (" + LOANS + ")" + (status == null ? "" : " WHERE status = ?")
                + " ORDER BY checked_out_on DESC, id DESC";
        try (Connection connection = dataSource.getConnection()) {
            return status == null
                    ? query(connection, sql, LibraryStore::loan, today())
                    : query(connection, sql, LibraryStore::loan, today(), status.name());
        }
    }

    Loan checkout(long bookId, long memberId) throws SQLException {
        LocalDate date = LocalDate.now(clock);
        return write(connection -> {
            Book book = requireBook(connection, bookId);
            requireMember(connection, memberId);
            if (exists(connection, """
                    SELECT id FROM loans WHERE book_id = ? AND member_id = ? AND returned_on IS NULL
                    """, bookId, memberId)) {
                throw new ApiException(409, "This member already has a copy of this book on loan.");
            }
            if (book.availableCopies() == 0) {
                throw new ApiException(409, "No copies of this book are available.");
            }
            return requireLoan(connection, insertLoan(connection, bookId, memberId, date, null), date.toString());
        });
    }

    Loan returnLoan(long id) throws SQLException {
        String date = today();
        return write(connection -> {
            Loan loan = requireLoan(connection, id, date);
            if (loan.returnedOn() != null) {
                throw new ApiException(409, "This loan has already been returned.");
            }
            update(connection, "UPDATE loans SET returned_on = ? WHERE id = ?", date, id);
            return requireLoan(connection, id, date);
        });
    }

    private static Book requireBook(Connection connection, long id) throws SQLException {
        return query(connection, BOOKS + " WHERE b.id = ?", LibraryStore::book, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "Book not found."));
    }

    private static Member requireMember(Connection connection, long id) throws SQLException {
        return query(connection, MEMBERS + " WHERE id = ?", LibraryStore::member, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "Member not found."));
    }

    private static Loan requireLoan(Connection connection, long id, String today) throws SQLException {
        return query(connection, LOANS + " WHERE l.id = ?", LibraryStore::loan, today, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "Loan not found."));
    }

    private static void checkIsbn(Connection connection, String isbn, long id) throws SQLException {
        if (exists(connection, "SELECT id FROM books WHERE isbn = ? AND id <> ?", isbn, id)) {
            throw new ApiException(409, "A book with this ISBN already exists.");
        }
    }

    private static void checkEmail(Connection connection, String email, long id) throws SQLException {
        if (exists(connection, "SELECT id FROM members WHERE email = ? AND id <> ?", email, id)) {
            throw new ApiException(409, "A member with this email address already exists.");
        }
    }

    private static long insertBook(Connection connection, BookInput input) throws SQLException {
        return insert(connection, INSERT_BOOK, input.isbn(), input.title(), input.author(), input.genre(), input.totalCopies());
    }

    private static long insertMember(Connection connection, MemberInput input) throws SQLException {
        return insert(connection, INSERT_MEMBER, input.name(), input.email());
    }

    private static long insertLoan(Connection connection, long bookId, long memberId, LocalDate checkout,
                                   LocalDate returned) throws SQLException {
        return insert(connection, INSERT_LOAN, bookId, memberId, checkout.toString(),
                checkout.plusDays(14).toString(), returned == null ? null : returned.toString());
    }

    private static Book book(ResultSet row) throws SQLException {
        return new Book(row.getLong("id"), row.getString("isbn"), row.getString("title"), row.getString("author"),
                row.getString("genre"), row.getInt("total_copies"), row.getInt("available_copies"));
    }

    private static Member member(ResultSet row) throws SQLException {
        return new Member(row.getLong("id"), row.getString("name"), row.getString("email"));
    }

    private static Loan loan(ResultSet row) throws SQLException {
        return new Loan(row.getLong("id"), row.getLong("book_id"), row.getString("book_title"),
                row.getLong("member_id"), row.getString("member_name"), row.getString("checked_out_on"),
                row.getString("due_on"), row.getString("returned_on"), LoanStatus.valueOf(row.getString("status")));
    }

    private <T> T write(Transaction<T> action) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection);
                // SQLite JDBC commit() opens another transaction when auto-commit is disabled.
                connection.setAutoCommit(true);
                return result;
            } catch (SQLException | RuntimeException failure) {
                try (var statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        update(connection, sql, values);
        return query(connection, "SELECT last_insert_rowid()", row -> row.getLong(1)).getFirst();
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static boolean exists(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static <T> List<T> query(Connection connection, String sql, RowMapper<T> mapper,
                                     Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.read(rows));
                }
                return result;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private String today() {
        return LocalDate.now(clock).toString();
    }

    private static boolean contains(String needle, String... fields) {
        for (String field : fields) {
            if (fold(field).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String fold(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T read(ResultSet row) throws SQLException;
    }
}
