package com.librarydesk.client;

import com.librarydesk.client.LibraryApi.ApiException;
import com.librarydesk.client.LibraryApi.Book;
import com.librarydesk.client.LibraryApi.BookInput;
import com.librarydesk.client.LibraryApi.CheckoutInput;
import com.librarydesk.client.LibraryApi.Loan;
import com.librarydesk.client.LibraryApi.LoanStatus;
import com.librarydesk.client.LibraryApi.Member;
import com.librarydesk.client.LibraryApi.MemberInput;
import com.librarydesk.client.LibraryApi.Stats;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LibraryDesk extends Application {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TODAY =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final PseudoClass SUCCESS = PseudoClass.getPseudoClass("success");

    private final String stylesheet = Objects.requireNonNull(
            LibraryDesk.class.getResource("desk.css")).toExternalForm();
    private final ExecutorService io = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "library-api");
        thread.setDaemon(true);
        return thread;
    });
    private final EnumMap<Section, Button> navigation = new EnumMap<>(Section.class);
    private final BorderPane shell = new BorderPane();
    private Stage window;
    private LibraryApi api;
    private Page current;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        window = stage;
        String configuredUrl = System.getenv("LIBRARY_API_URL");
        api = new LibraryApi(configuredUrl == null || configuredUrl.isBlank()
                ? "http://127.0.0.1:8080/api" : configuredUrl);
        shell.getStyleClass().add("desk");
        shell.setLeft(sidebar());
        Scene scene = new Scene(shell, 1190, 760);
        scene.getStylesheets().add(stylesheet);
        window.setTitle("Library Desk");
        window.setMinWidth(990);
        window.setMinHeight(600);
        window.setScene(scene);
        show(Section.OVERVIEW);
        window.show();
    }

    @Override
    public void stop() {
        io.shutdownNow();
        api.close();
    }

    private VBox sidebar() {
        Label mark = label("L", "brand-mark");
        Label name = label("Library Desk", "brand-name");
        HBox brand = row(11, mark, name);
        brand.getStyleClass().add("brand");

        VBox sidebar = new VBox(6, brand);
        sidebar.getStyleClass().add("sidebar");
        for (Section section : Section.values()) {
            Button item = button(section.title, "nav-item", () -> show(section));
            item.setMaxWidth(Double.MAX_VALUE);
            navigation.put(section, item);
            sidebar.getChildren().add(item);
        }
        return sidebar;
    }

    private void show(Section section) {
        if (current != null) {
            current.cancel();
        }
        navigation.forEach((destination, button) ->
                button.pseudoClassStateChanged(CURRENT, destination == section));
        current = new Page(section);
        switch (section) {
            case OVERVIEW -> overview(current);
            case CATALOG -> catalog(current);
            case MEMBERS -> members(current);
            case CIRCULATION -> circulation(current);
        }
        shell.setCenter(current.content);
        current.reload.run();
    }

    private void overview(Page page) {
        Label date = label(LocalDate.now().format(TODAY), "hero-date");
        Label deskTitle = label("Today at the library", "hero-title");
        HBox links = row(10,
                button("Browse catalog", "hero-button", () -> show(Section.CATALOG)),
                button("Manage circulation", "hero-secondary", () -> show(Section.CIRCULATION)));
        VBox hero = new VBox(8, date, deskTitle, links);
        hero.getStyleClass().add("hero");
        VBox.setMargin(links, new Insets(8, 0, 0, 0));

        Label titles = label("—", "metric-value");
        Label members = label("—", "metric-value");
        Label open = label("—", "metric-value");
        Label overdue = label("—", "metric-value");
        GridPane metrics = new GridPane();
        metrics.setHgap(14);
        metrics.add(metric("Catalog titles", titles, "metric-green"), 0, 0);
        metrics.add(metric("Members", members, "metric-blue"), 1, 0);
        metrics.add(metric("Open loans", open, "metric-gold"), 2, 0);
        metrics.add(metric("Overdue", overdue, "metric-red"), 3, 0);
        for (int index = 0; index < 4; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(25);
            metrics.getColumnConstraints().add(column);
        }

        TableView<Loan> recent = loanTable(true);
        placeholder(page, recent, "No circulation activity yet");
        VBox activity = new VBox(10, label("Recent activity", "section-title"), tableCard(recent));
        VBox.setVgrow(activity.getChildren().getLast(), Priority.ALWAYS);
        VBox.setVgrow(activity, Priority.ALWAYS);
        page.content.getChildren().addAll(hero, metrics, activity);
        page.reload = () -> page.fetch(() -> new Overview(api.stats(), api.loans(null)), result -> {
            titles.setText(Integer.toString(result.stats().titles()));
            members.setText(Integer.toString(result.stats().members()));
            open.setText(Integer.toString(result.stats().openLoans()));
            overdue.setText(Integer.toString(result.stats().overdueLoans()));
            replaceItems(recent, result.loans().stream()
                    .sorted(Comparator.comparingLong(Loan::id).reversed()).limit(5).toList());
        });
    }

    private static VBox metric(String title, Label value, String accent) {
        VBox tile = new VBox(7, label(title, "metric-caption"), value);
        tile.getStyleClass().addAll("metric", accent);
        return tile;
    }

    private void catalog(Page page) {
        TableView<Book> books = table();
        placeholder(page, books, "No books found");
        books.getColumns().addAll(List.of(
                column("Title", 205, Book::title),
                column("Author", 145, Book::author),
                column("ISBN", 133, Book::isbn),
                column("Genre", 115, book -> book.genre().isBlank() ? "—" : book.genre()),
                bookAvailability()));
        TextField search = searchField("Search title, author, ISBN, or genre");
        Button edit = button("Edit book", "secondary", () -> bookDialog(page, selected(books)));
        Button add = button("Add book", "primary", () -> bookDialog(page, null));
        edit.disableProperty().bind(books.getSelectionModel().selectedItemProperty().isNull().or(page.busy));
        add.disableProperty().bind(page.busy);
        addIndex(page, row(9, search, spacer(), edit, add), books);
        page.reload = () -> {
            String query = search.getText();
            page.fetch(() -> api.books(query), result -> replaceItems(books, result));
        };
        watchSearch(page, search);
    }

    private void members(Page page) {
        TableView<Member> members = table();
        placeholder(page, members, "No members found");
        members.getColumns().addAll(List.of(
                column("Name", 250, Member::name),
                column("Email", 305, Member::email),
                column("Member ID", 130, member -> "#" + member.id())));
        TextField search = searchField("Search name or email");
        Button edit = button("Edit member", "secondary", () -> memberDialog(page, selected(members)));
        Button add = button("Register member", "primary", () -> memberDialog(page, null));
        edit.disableProperty().bind(members.getSelectionModel().selectedItemProperty().isNull().or(page.busy));
        add.disableProperty().bind(page.busy);
        addIndex(page, row(9, search, spacer(), edit, add), members);
        page.reload = () -> {
            String query = search.getText();
            page.fetch(() -> api.members(query), result -> replaceItems(members, result));
        };
        watchSearch(page, search);
    }

    private void circulation(Page page) {
        TableView<Loan> loans = loanTable(false);
        placeholder(page, loans, "No loans found");
        ComboBox<LoanFilter> filter = new ComboBox<>(FXCollections.observableArrayList(
                new LoanFilter("All loans", null), new LoanFilter("Open", LoanStatus.OPEN),
                new LoanFilter("Overdue", LoanStatus.OVERDUE), new LoanFilter("Returned", LoanStatus.RETURNED)));
        filter.setAccessibleText("Loan status");
        filter.setPrefWidth(155);
        filter.getSelectionModel().selectFirst();
        Button checkIn = button("Return book", "secondary", () -> returnDialog(page, selected(loans)));
        Button checkout = button("Check out book", "primary", () -> prepareCheckout(page));
        checkIn.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            Loan selected = selected(loans);
            return page.busy.get() || selected == null || selected.status() == LoanStatus.RETURNED;
        }, page.busy, loans.getSelectionModel().selectedItemProperty()));
        checkout.disableProperty().bind(page.busy);
        addIndex(page, row(9, filter, spacer(), checkIn, checkout), loans);
        page.reload = () -> {
            LoanStatus status = filter.getValue().status();
            page.fetch(() -> api.loans(status), result -> replaceItems(loans, result));
        };
        filter.setOnAction(event -> page.reload.run());
    }

    private void addIndex(Page page, HBox toolbar, TableView<?> table) {
        Region card = tableCard(table);
        VBox.setVgrow(card, Priority.ALWAYS);
        page.content.getChildren().addAll(toolbar, card);
    }

    private static void placeholder(Page page, TableView<?> table, String fallback) {
        Label empty = label(fallback, "table-empty");
        empty.textProperty().bind(Bindings.when(page.busy).then("Loading…").otherwise(fallback));
        table.setPlaceholder(empty);
    }

    private static <T> void replaceItems(TableView<T> table, List<T> result) {
        table.getSelectionModel().clearSelection();
        table.getItems().setAll(result);
        table.sort();
    }

    private void watchSearch(Page page, TextField search) {
        PauseTransition delay = new PauseTransition(Duration.millis(240));
        delay.setOnFinished(event -> {
            if (current == page) {
                page.reload.run();
            }
        });
        search.textProperty().addListener((observable, previous, next) -> delay.playFromStart());
        search.setOnAction(event -> {
            delay.stop();
            page.reload.run();
        });
    }

    private void bookDialog(Page page, Book existing) {
        boolean editing = existing != null;
        TextField title = field(editing ? existing.title() : "");
        TextField author = field(editing ? existing.author() : "");
        TextField isbn = field(editing ? existing.isbn() : "");
        TextField genre = field(editing ? existing.genre() : "");
        TextField copies = field(editing ? Integer.toString(existing.totalCopies()) : "1");
        VBox form = new VBox(12, named("Title", title), named("Author", author), named("ISBN", isbn),
                named("Genre (optional)", genre), named("Total copies", copies));
        showDialog(page, editing ? "Edit book" : "Add book", "Save book", form, () -> {
            String validTitle = required(title, "Title", 200);
            String validAuthor = required(author, "Author", 160);
            String validIsbn = required(isbn, "ISBN", 32);
            String validGenre = genre.getText().strip();
            if (validGenre.length() > 80) {
                throw new IllegalArgumentException("Genre can be at most 80 characters.");
            }
            int count = copyCount(copies.getText());
            if (editing && count < existing.totalCopies() - existing.availableCopies()) {
                throw new IllegalArgumentException("Total copies must cover every copy currently checked out.");
            }
            BookInput input = new BookInput(validIsbn, validTitle, validAuthor, validGenre, count);
            return () -> api.saveBook(existing, input);
        }, ignored -> editing ? "Book updated." : "Book added to the catalog.");
    }

    private void memberDialog(Page page, Member existing) {
        boolean editing = existing != null;
        TextField name = field(editing ? existing.name() : "");
        TextField email = field(editing ? existing.email() : "");
        VBox form = new VBox(12, named("Name", name), named("Email", email));
        showDialog(page, editing ? "Edit member" : "Register member", "Save member", form, () -> {
            String validName = required(name, "Name", 120);
            String validEmail = required(email, "Email", 254);
            if (!validEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new IllegalArgumentException("Enter an email address such as name@example.com.");
            }
            MemberInput input = new MemberInput(validName, validEmail);
            return () -> api.saveMember(existing, input);
        }, ignored -> editing ? "Member updated." : "Member registered.");
    }

    private void prepareCheckout(Page page) {
        page.fetch(() -> new CheckoutOptions(
                api.books("").stream().filter(book -> book.availableCopies() > 0).toList(),
                api.members("")), options -> {
            if (options.books().isEmpty() || options.members().isEmpty()) {
                page.alert(options.books().isEmpty()
                        ? "There are no available copies to check out."
                        : "Register a member before checking out a book.");
                return;
            }
            checkoutDialog(page, options);
        });
    }

    private void checkoutDialog(Page page, CheckoutOptions options) {
        ComboBox<Choice<Book>> books = new ComboBox<>(FXCollections.observableArrayList(
                options.books().stream().map(book -> new Choice<>(
                        book.title() + " · " + book.isbn() + " · " + book.availableCopies() + " available",
                        book)).toList()));
        books.setPromptText("Choose an available book");
        books.setMaxWidth(Double.MAX_VALUE);
        ComboBox<Choice<Member>> members = new ComboBox<>(FXCollections.observableArrayList(
                options.members().stream().map(member ->
                        new Choice<>(member.name() + " · " + member.email(), member)).toList()));
        members.setPromptText("Choose a member");
        members.setMaxWidth(Double.MAX_VALUE);
        VBox form = new VBox(12, named("Book", books), named("Member", members),
                label("Books are due 14 days after checkout.", "helper-text"));
        showDialog(page, "Check out book", "Check out", form, () -> {
            if (books.getValue() == null || members.getValue() == null) {
                throw new IllegalArgumentException("Choose a book and a member.");
            }
            CheckoutInput input = new CheckoutInput(books.getValue().value().id(), members.getValue().value().id());
            return () -> api.checkout(input);
        }, loan -> "Checkout recorded. Due " + DATE.format(LocalDate.parse(loan.dueOn())) + ".");
    }

    private void returnDialog(Page page, Loan loan) {
        Label details = label(loan.bookTitle() + " borrowed by " + loan.memberName() + ".", "helper-text");
        details.setWrapText(true);
        VBox form = new VBox(10, details, label("The copy will be available for checkout again.", "helper-text"));
        showDialog(page, "Return book", "Confirm return", form, () -> () -> api.returnLoan(loan.id()),
                ignored -> "Book returned.");
    }

    private <T> void showDialog(Page page, String title, String action, VBox form,
                                Supplier<Callable<T>> validate, Function<T, String> confirmation) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(window);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(stylesheet);
        pane.getStyleClass().add("desk-dialog");
        pane.setPrefWidth(540);
        pane.setHeader(label(title, "dialog-title"));
        Label feedback = label("", "dialog-error");
        feedback.setWrapText(true);
        showWhenFilled(feedback);
        ProgressIndicator spinner = spinner();
        spinner.managedProperty().bind(spinner.visibleProperty());
        pane.setContent(new VBox(12, form, row(10, spinner, feedback)));
        ButtonType save = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, save);
        Node saveButton = pane.lookupButton(save);
        saveButton.getStyleClass().add("primary");
        BooleanProperty saving = new SimpleBooleanProperty(false);
        form.disableProperty().bind(saving);
        spinner.visibleProperty().bind(saving);
        saveButton.disableProperty().bind(saving);
        pane.lookupButton(ButtonType.CANCEL).disableProperty().bind(saving);
        dialog.setOnCloseRequest(event -> {
            if (saving.get()) {
                event.consume();
            }
        });
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            feedback.setText("");
            Callable<T> request;
            try {
                request = validate.get();
            } catch (IllegalArgumentException exception) {
                feedback.setText(exception.getMessage());
                return;
            }
            saving.set(true);
            submit(request, result -> {
                saving.set(false);
                dialog.close();
                page.reload.run();
                page.message(confirmation.apply(result), true);
            }, failure -> {
                saving.set(false);
                feedback.setText(errorText(failure));
            });
        });
        dialog.show();
    }

    private static int copyCount(String value) {
        try {
            int count = Integer.parseInt(value.strip());
            if (count >= 1 && count <= 999) {
                return count;
            }
        } catch (NumberFormatException ignored) {
            // Use the same validation message for any invalid value.
        }
        throw new IllegalArgumentException("Enter a whole number from 1 to 999 for total copies.");
    }

    private static String required(TextField field, String name, int limit) {
        String text = field.getText().strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        if (text.length() > limit) {
            throw new IllegalArgumentException(name + " can be at most " + limit + " characters.");
        }
        return text;
    }

    private static String errorText(Throwable failure) {
        if (failure instanceof ApiException) {
            return failure.getMessage();
        }
        System.getLogger(LibraryDesk.class.getName()).log(System.Logger.Level.ERROR, "Library request failed", failure);
        return "Something went wrong. Try again.";
    }

    private <T> Task<T> submit(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> success.accept(task.getValue()));
        task.setOnFailed(event -> failure.accept(task.getException()));
        io.execute(task);
        return task;
    }

    private static <T> TableView<T> table() {
        TableView<T> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(48);
        return table;
    }

    private static TableView<Loan> loanTable(boolean recent) {
        TableView<Loan> table = table();
        table.getColumns().addAll(List.of(
                column("Book", 165, Loan::bookTitle),
                column("Member", 125, Loan::memberName),
                dateColumn("Checked out", 105, Loan::checkedOutOn),
                dateColumn("Due", 100, Loan::dueOn)));
        if (!recent) {
            table.getColumns().add(dateColumn("Returned", 102, Loan::returnedOn));
        }
        TableColumn<Loan, LoanStatus> status = column("Status", 110, Loan::status);
        status.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(LoanStatus value, boolean empty) {
                super.updateItem(value, empty);
                setGraphic(empty || value == null ? null
                        : label(value.label(), "badge", "badge-" + value.name().toLowerCase(Locale.ROOT)));
            }
        });
        table.getColumns().add(status);
        return table;
    }

    private static TableColumn<Book, Book> bookAvailability() {
        TableColumn<Book, Book> availability = column("Availability", 128, Function.identity());
        availability.setComparator(Comparator.comparingInt(Book::availableCopies));
        availability.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Book book, boolean empty) {
                super.updateItem(book, empty);
                setGraphic(empty || book == null ? null
                        : label(book.availableCopies() + " / " + book.totalCopies() + " available", "badge",
                                book.availableCopies() > 0 ? "badge-open" : "badge-overdue"));
            }
        });
        return availability;
    }

    private static <T, V> TableColumn<T, V> column(String title, int width, Function<T, V> value) {
        TableColumn<T, V> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    private static TableColumn<Loan, LocalDate> dateColumn(String title, int width, Function<Loan, String> value) {
        TableColumn<Loan, LocalDate> date = column(title, width, loan -> {
            String raw = value.apply(loan);
            return raw == null ? null : LocalDate.parse(raw);
        });
        date.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDate day, boolean empty) {
                super.updateItem(day, empty);
                setText(empty ? null : day == null ? "—" : DATE.format(day));
            }
        });
        return date;
    }

    private static Region tableCard(TableView<?> table) {
        VBox card = new VBox(table);
        card.getStyleClass().add("table-card");
        VBox.setVgrow(table, Priority.ALWAYS);
        return card;
    }

    private static <T> T selected(TableView<T> table) {
        return table.getSelectionModel().getSelectedItem();
    }

    private static TextField searchField(String prompt) {
        TextField field = field("");
        field.setPromptText(prompt);
        field.setAccessibleText(prompt);
        field.setPrefWidth(315);
        field.getStyleClass().add("search");
        return field;
    }

    private static TextField field(String content) {
        return new TextField(content);
    }

    private static VBox named(String text, javafx.scene.control.Control control) {
        Label name = label(text, "field-label");
        name.setLabelFor(control);
        return new VBox(5, name, control);
    }

    private static void showWhenFilled(Label label) {
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
    }

    private static Label label(String value, String... styles) {
        Label label = new Label(value);
        label.getStyleClass().addAll(styles);
        return label;
    }

    private static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static ProgressIndicator spinner() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(21, 21);
        spinner.setMaxSize(21, 21);
        return spinner;
    }

    private static HBox row(double spacing, Node... children) {
        HBox row = new HBox(spacing, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private final class Page {
        private final VBox content = new VBox(17);
        private final BooleanProperty busy = new SimpleBooleanProperty();
        private final Label feedback = label("", "page-error");
        private Runnable reload;
        private Task<?> pending;

        private Page(Section section) {
            content.getStyleClass().add("workspace");
            Label title = label(section.title, "page-title");
            ProgressIndicator progress = spinner();
            Button refresh = button("Refresh", "secondary", () -> reload.run());
            HBox header = row(10, title, spacer(), progress, refresh);
            progress.visibleProperty().bind(busy);
            refresh.disableProperty().bind(busy);
            feedback.setWrapText(true);
            feedback.setMaxWidth(Double.MAX_VALUE);
            showWhenFilled(feedback);
            content.getChildren().addAll(header, feedback);
        }

        private <T> void fetch(Callable<T> work, Consumer<T> success) {
            cancel();
            alert("");
            busy.set(true);
            pending = submit(work, result -> {
                pending = null;
                busy.set(false);
                success.accept(result);
            }, failure -> {
                pending = null;
                busy.set(false);
                alert(errorText(failure));
            });
        }

        private void cancel() {
            if (pending != null) {
                pending.cancel();
                pending = null;
            }
        }

        private void alert(String message) {
            message(message, false);
        }

        private void message(String message, boolean success) {
            feedback.pseudoClassStateChanged(SUCCESS, success);
            feedback.setText(message);
        }
    }

    private enum Section {
        OVERVIEW("Overview"), CATALOG("Catalog"), MEMBERS("Members"), CIRCULATION("Circulation");

        private final String title;

        Section(String title) {
            this.title = title;
        }
    }

    private record Overview(Stats stats, List<Loan> loans) {}
    private record CheckoutOptions(List<Book> books, List<Member> members) {}
    private record LoanFilter(String label, LoanStatus status) {
        @Override
        public String toString() {
            return label;
        }
    }
    private record Choice<T>(String label, T value) {
        @Override
        public String toString() {
            return label;
        }
    }
}
