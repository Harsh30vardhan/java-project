package com.librarydesk.server;

public final class LibraryRecords {
    private LibraryRecords() {}

    public record BookInput(String isbn, String title, String author, String genre, int totalCopies) {}

    public record Book(long id, String isbn, String title, String author, String genre,
                       int totalCopies, int availableCopies) {}

    public record MemberInput(String name, String email) {}

    public record Member(long id, String name, String email) {}

    public record Loan(long id, long bookId, String bookTitle, long memberId, String memberName,
                       String checkedOutOn, String dueOn, String returnedOn, LoanStatus status) {}

    public enum LoanStatus { OPEN, OVERDUE, RETURNED }

    public record Stats(long titles, long members, long openLoans, long overdueLoans) {}
}
