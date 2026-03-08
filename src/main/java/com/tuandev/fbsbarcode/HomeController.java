package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.*;
import com.tuandev.fbsbarcode.services.*;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.effect.InnerShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class HomeController implements Initializable {
    public VBox leftPane;
    public VBox shopPane;
    public BorderPane contentPane;
    public Label currentShopLabel;
    public TableView<Order> orderTable;
    public TableColumn<Order, Integer> noTC;
    public TableColumn<Order, String> idTC;
    public TableColumn<Order, String> nameTC;
    public TableColumn<Order, String> articleTC;
    public TableColumn<Order, String> colorTC;
    public TableColumn<Order, String> sizeTC;
    public TableColumn<Order, String> stickerTC;
    public TableColumn<Order, String> barcodeTC;
    public TableColumn<Order, String> stickerCodeTC;
    public ProgressBar loading;
    public VBox categoryVBox;
    public VBox rightPage;
    public TextArea kizCommand;

    private List<Shop> shops = new ArrayList<>();
    private List<Order> orders = new ArrayList<>();
    private List<Category> categories = new ArrayList<>();
    private Set<String> categoriesWB = new HashSet<>();
    private Shop selectedShop;
    private HBox selectedBox;
    private FileChooser fileChooser;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        Database.initDatabase();

        Task<List<CategoryWB>> loadCategoryWbTask = new Task<>() {
            @Override
            protected List<CategoryWB> call() throws Exception {
                return CategoryService.loadCategories();
            }
        };
        loadCategoryWbTask.setOnSucceeded(event -> {
            categoriesWB = loadCategoryWbTask.getValue()
                    .stream().map(CategoryWB::getSubjectName)
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        });
        new Thread(loadCategoryWbTask).start();

        fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home"), "Downloads"));

        noTC.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Integer integer, boolean empty) {
                super.updateItem(integer, empty);

                if (empty) {
                    setText(null);
                } else {
                    setText(String.valueOf(getIndex() + 1));
                }
            }
        });
        idTC.setCellValueFactory(new PropertyValueFactory<>("id"));
        nameTC.setCellValueFactory(new PropertyValueFactory<>("name"));
        articleTC.setCellValueFactory(new PropertyValueFactory<>("article"));
        colorTC.setCellValueFactory(new PropertyValueFactory<>("color"));
        sizeTC.setCellValueFactory(new PropertyValueFactory<>("size"));
        stickerTC.setCellValueFactory(new PropertyValueFactory<>("sticker"));
        barcodeTC.setCellValueFactory(new PropertyValueFactory<>("barcode"));
        stickerCodeTC.setCellValueFactory(new PropertyValueFactory<>("stickerCode"));

        contentPane.setVisible(false);
        rightPage.setVisible(false);
        loadShops();
    }

    private void loadShops() {
        Task<List<Shop>> task = new Task<>() {
            @Override
            protected List<Shop> call() throws Exception {
                return ShopService.getAllShops();
            }

        };
        task.setOnSucceeded(e -> {
            shops = task.getValue();
            if (shops.isEmpty()) {
                return;
            }
            loadShopToView();
        });
        task.setOnFailed(e -> {
            showError(e.getSource().getException().getMessage());
        });
        new Thread(task).start();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi hệ thống");
        alert.setHeaderText(message);
        alert.showAndWait();
    }

    private void loadShopToView() {
        shopPane.getChildren().clear();

        Image image = new Image(
                Objects.requireNonNull(getClass().getResource("shopping-cart.png")).toExternalForm()
        );

        for (Shop shop : shops) {
            HBox hBox = new HBox();
            hBox.setCursor(Cursor.HAND);
            hBox.setAlignment(Pos.CENTER_LEFT);
            hBox.getStyleClass().addAll("bg-info");
            hBox.getStyle();

            hBox.setPadding(new Insets(6));
            hBox.setSpacing(6);

            ImageView shopIcon = new ImageView(image);
            Label shopName = new Label(shop.getName());

            hBox.getChildren().addAll(shopIcon, shopName);

            hBox.setOnMouseClicked(e -> {

                if (selectedBox != null) {
                    selectedBox.getStyleClass().remove("bg-primary");
                    selectedBox.getStyleClass().add("bg-info");
                }

                hBox.getStyleClass().remove("bg-info");
                hBox.getStyleClass().add("bg-primary");

                selectedBox = hBox;
                selectedShop = shop;

                currentShopLabel.setText(selectedShop.getName());

                orders.clear();
                orderTable.getItems().clear();

                loadCategories();

                if (!contentPane.isVisible()) {
                    contentPane.setVisible(true);
                }

                if (!rightPage.isVisible()) {
                    rightPage.setVisible(true);
                }
            });

            hBox.setOnMouseEntered(e -> {
                if (hBox != selectedBox) {
                    hBox.getStyleClass().remove("bg-info");
                    hBox.getStyleClass().add("bg-primary");
                }
            });

            hBox.setOnMouseExited(e -> {
                if (hBox != selectedBox) {
                    hBox.getStyleClass().remove("bg-primary");
                    hBox.getStyleClass().add("bg-info");
                }
            });

            shopPane.getChildren().add(hBox);
        }
    }

    public void onAddShop(ActionEvent actionEvent) {
        Dialog<ButtonType> dialogAddShop = new Dialog<>();
        dialogAddShop.setTitle("Thêm cửa hàng");

        VBox vBox = new VBox(8);
        vBox.setPrefWidth(300);
        vBox.setPadding(new Insets(20));

        Label nameShopLabel = new Label("Tên cửa hàng *");
        TextField nameField = new TextField();

        Label apiKeyShopLabel = new Label("API Key *");
        TextField apiKeyField = new TextField();

        vBox.getChildren().addAll(nameShopLabel, nameField, apiKeyShopLabel, apiKeyField);

        dialogAddShop.getDialogPane().setContent(vBox);

        ButtonType addBtn = new ButtonType("Thêm", ButtonType.OK.getButtonData());
        ButtonType cancelBtn = new ButtonType("Hủy", ButtonType.CANCEL.getButtonData());

        dialogAddShop.getDialogPane().getButtonTypes().addAll(cancelBtn,  addBtn);

        Optional<ButtonType> result = dialogAddShop.showAndWait();

        if (result.isPresent() && result.get() == addBtn) {
            String name = nameField.getText();
            String apiKey = apiKeyField.getText();

            if (!name.isBlank() && !apiKey.isBlank()) {
                Shop shop = new Shop(name.trim(), apiKey.trim());
                int count = ShopService.addShop(shop);
                if (count > 0) {
                    loadShops();
                }
            }
        }
    }

    private String extractProductType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String lower = name.toLowerCase();

        List<String> productTypes = List.of(
                "куртка", "ветровка", "бомбер", "пальто", "жилет",
                "джинсы", "брюки", "толстовка", "худи", "свитшот"
        );

        for (String type : productTypes) {
            if (lower.contains(type)) {
                return type;
            }
        }

        return lower;
    }

    private String extractCategoryFromName(String productName, Set<String> categoriesWB) {
        if (productName == null || productName.isBlank()) {
            return null;
        }

        String normalizedName = normalizeText(productName);

        for (String category : categoriesWB) {
            if (normalizedName.contains(category)) {
                return category;
            }
        }

        return null;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }

        return text.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public void onUploadExcel(ActionEvent actionEvent) {
        fileChooser.setTitle("Open Excel File");
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));

        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            try {
                Task<List<Order>> loadOrdersTask = new Task<>() {
                    @Override
                    protected List<Order> call() throws Exception {
                        List<Order> newOrderList = OrderService.getOrdersToExcel(file);
                        Comparator<String> stringComparator =
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
                        newOrderList.sort(
                                Comparator.comparing((Order o) -> extractCategoryFromName(o.getName(), categoriesWB),
                                                stringComparator)
                                        .thenComparing(Order::getArticle, stringComparator)
                                        .thenComparing(Order::getColor, stringComparator)
                                        .thenComparing(Order::getSize, stringComparator)
                        );
                        return newOrderList;
                    }
                };
                loadOrdersTask.setOnRunning(e -> {
                    loading.setVisible(true);
                });
                loadOrdersTask.setOnFailed(e -> {
                    e.getSource().getException().printStackTrace();
                    showError(e.getSource().getException().getMessage());
                    loading.setVisible(false);
                });
                loadOrdersTask.setOnSucceeded(e -> {
                    orders = loadOrdersTask.getValue();
                    if (!orders.isEmpty()) {
                        List<Long> orderIds = orders.stream().map(Order::getId).toList();
                        Task<List<Sticker>> loadStickersTask = new Task<>() {
                            @Override
                            protected List<Sticker> call() throws Exception {
                                return OrderService.getStickers(selectedShop.getApiKey(), orderIds);
                            }
                        };
                        loadStickersTask.setOnSucceeded(e2 -> {
                            loading.setVisible(false);
                            List<Sticker> stickers = loadStickersTask.getValue();

                            if (stickers.isEmpty()) {
                                showError("Không lấy được mã vận đơn! Vui lòng kiểm tra lại\ncửa hàng hoặc API KEY");
                                return;
                            }
                            Map<Long, String> stickerMap = stickers.stream()
                                    .collect(Collectors.toMap(
                                            Sticker::getOrderId,
                                            Sticker::getBarcode
                                    ));

                            for (Order order : orders) {
                                String barcode = stickerMap.get(order.getId());
                                if (barcode != null) {
                                    order.setStickerCode(barcode);
                                }
                            }

                            orderTable.setItems(FXCollections.observableArrayList(orders));
                            orderTable.refresh();
                        });
                        loadStickersTask.setOnFailed(e2 -> {
                            loading.setVisible(false);
                            showError("Không lấy được mã vận đơn! Vui lòng kiểm tra lại API KEY\ncửa hàng hoặc API KEY");
                            e2.getSource().getException().printStackTrace();
                        });
                        new Thread(loadStickersTask).start();
                    }
                });
                new Thread(loadOrdersTask).start();
            } catch (Exception e) {
                showError(e.getMessage());
            }
        }
    }

    public void onExport(ActionEvent actionEvent) {
        if (orders.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Thông báo");
            alert.setHeaderText("Vui lòng cập nhật đơn hàng");
            alert.showAndWait();
            return;
        }
        // Get Kizs
        for (Order order : orders) {
            order.setKiz(null);
        }
        String command = kizCommand.getText();
        List<Kiz> usedKizList = new ArrayList<>();
        if (!command.isBlank()) {
            String[] commands = command.split("\\R");
            for (String commandLine : commands) {
                if (commandLine == null || commandLine.isBlank()) {
                    continue;
                }
                try {
                    String[] idAndRange = commandLine.trim().split(":");
                    if (idAndRange.length != 2) {
                        showError("Sai định dạng: " + commandLine + " | Đúng: ID:FROM-TO");
                        return;
                    }

                    int categoryId = Integer.parseInt(idAndRange[0].trim());
                    String[] fromTo = idAndRange[1].trim().split("-");
                    if (fromTo.length != 2) {
                        showError("Sai khoảng FROM-TO: " + commandLine);
                        return;
                    }

                    int from = Integer.parseInt(fromTo[0].trim());
                    int to = Integer.parseInt(fromTo[1].trim());

                    if (from < 1 || to < 1 || from > to) {
                        showError("Khoảng KIZ không hợp lệ: " + commandLine);
                        return;
                    }

                    if (to > orders.size()) {
                        showError("Vị trí order vượt quá số lượng đơn: " + commandLine);
                        return;
                    }

                    int count = to - from + 1;

                    List<Kiz> kizListItem = KizService.getKizs(selectedShop.getId(), categoryId, count);

                    if (kizListItem.isEmpty() || kizListItem.size() != count) {
                        showError("Không lấy đủ KIZ cho dòng: " + commandLine);
                        return;
                    }

                    for (int i = 0; i < count; i++) {
                        int orderIndex = from - 1 + i;

                        // chống overlap: cùng 1 order bị gán KIZ 2 lần
                        if (orders.get(orderIndex).getKiz() != null) {
                            showError("Order thứ " + (orderIndex + 1) + " bị gán KIZ trùng nhau");
                            return;
                        }

                        Kiz kiz = kizListItem.get(i);
                        orders.get(orderIndex).setKiz(kiz.getCode());
                        usedKizList.add(kiz);
                    }
                } catch (NumberFormatException e) {
                    showError("Yêu cầu lấy KIZ chưa đúng. Định dạng đúng: ID:FROM-TO");
                    return;
                } catch (Exception e) {
                    showError("Lỗi xử lý dòng: " + commandLine + "\n" + e.getMessage());
                    return;
                }
            }

        }

        // Export PDF
        fileChooser.setTitle("Open PDF File");
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            File orderDetailsFile = new File(
                    file.getParent(),
                    "NHAT_HANG-" + file.getName()
            );

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    int type = ConfigService.getPrintType();

                    // 1. Export PDF
                    if (type == 1) {
                        GenerateBarcode.type1(orders, file);
                    } else if (type == 2) {
                        GenerateBarcode.type2(orders, file);
                    } else {
                        GenerateBarcode.type3(orders, file);
                    }

                    OrderService.exportOrdersToPdf(orderDetailsFile, orders);

                    // 2. Delete Kiz
                    if (!usedKizList.isEmpty()) {
                        KizService.deleteKizs(usedKizList);
                    }

                    // 3. Post Kiz lên WB
                    if (!usedKizList.isEmpty()) {
                        for (Order order : orders) {
                            if (order.getKiz() == null || order.getKiz().isBlank()) {
                                continue; // order không cần KIZ thì bỏ qua
                            }

                            KizService.addDataMatrixCodeToOrder(
                                    selectedShop.getApiKey(),
                                    order.getId(),
                                    order.getKiz()
                            );

                            Thread.sleep(70);
                        }
                    }

                    return null;
                }
            };

            task.setOnRunning(e -> {
                loading.setVisible(true);
            });

            task.setOnFailed(e -> {
                loading.setVisible(false);

                Throwable ex = task.getException();
                showError(ex.getMessage());
                ex.printStackTrace();
            });

            task.setOnSucceeded(e -> {
                loading.setVisible(false);

                loadCategories();

                try {
                    Desktop.getDesktop().open(orderDetailsFile);
                    Desktop.getDesktop().open(file);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            });

            new Thread(task).start();
        }

    }

    public void onAddCategory(ActionEvent actionEvent) {
        Dialog<ButtonType> dialogAddCategory = new Dialog<>();
        dialogAddCategory.setTitle("Thêm danh mục sản phẩm");
        VBox vBox = new VBox(8);
        vBox.setPadding(new Insets(10));

        Label idLabel =  new Label("ID (number) *");
        TextField idField = new TextField();
        idField.setPromptText("Id là số");

        Label nameLabel =  new Label("Tên danh mục *");
        TextField nameField = new TextField();
        nameField.setPromptText("Tên danh mục");

        ButtonType okBtnType = new ButtonType("Thêm", ButtonType.OK.getButtonData());
        ButtonType cancelBtnType = new ButtonType("Hủy", ButtonType.CANCEL.getButtonData());
        dialogAddCategory.getDialogPane().getButtonTypes().addAll(okBtnType, cancelBtnType);

        vBox.getChildren().addAll(idLabel, idField, nameLabel, nameField);
        dialogAddCategory.getDialogPane().setContent(vBox);

        Optional<ButtonType> result = dialogAddCategory.showAndWait();
        if (result.isPresent() && result.get() == okBtnType) {
            try {
                if (idField.getText().isBlank() || nameField.getText().isBlank()) {
                    return;
                }

                int newId = Integer.parseInt(idField.getText().trim());
                String newName = nameField.getText().trim();

                int rowCount = CategoryService.createCategory(new Category(newId, newName));
                if (rowCount > 0) {
                    loadCategories();
                }
            } catch (NumberFormatException e) {
                showError("Id là số nguyên");
            } catch (SQLException e) {
                e.printStackTrace();
                showError("ID đã tồn tại! Vui lòng nhập ID khác");
            }
        }
    }

    private void loadCategories() {
        Task<List<Category>> loadCategoriesTask = new Task<>() {
            @Override
            protected List<Category> call() throws Exception {
                return CategoryService.getAllCategories(selectedShop.getId());
            }
        };
        loadCategoriesTask.setOnSucceeded(e -> {
            List<Category> categories = loadCategoriesTask.getValue();

            categoryVBox.getChildren().clear();

            for (Category category : categories) {
                categoryVBox.getChildren().add(addCategoryItem(category));
            }
        });
        new Thread(loadCategoriesTask).start();
    }

    private HBox addCategoryItem(Category category) {
        HBox hBox = new HBox();
        hBox.setPadding(new Insets(2));
        hBox.setSpacing(4);
        hBox.setAlignment(Pos.CENTER_LEFT);
        hBox.setMinHeight(40);
        hBox.setPrefWidth(182);
        hBox.getStyleClass().add("bg-warning");
        hBox.setBorder(new Border(new BorderStroke(
                Color.LIGHTGRAY,
                BorderStrokeStyle.SOLID,
                CornerRadii.EMPTY,
                new BorderWidths(0, 0, 1, 0)
        )));

        Label idLabel = new Label("ID: " + category.getId());
        idLabel.setPrefWidth(46);

        Label nameLabel = new Label(category.getName());
        nameLabel.setPrefWidth(95);

        TextField countKizsField = new TextField();
        countKizsField.setEditable(false);
        countKizsField.setAlignment(Pos.CENTER);
        countKizsField.setPrefWidth(46);
        countKizsField.setText(String.valueOf(category.getCountKiz()));

        Button addKizBtn = new Button(" + ");
        addKizBtn.setStyle(
                "-fx-background-color: #0d6efd;" +
                        "-fx-text-fill: #e8e8e8;" +
                        "-fx-font-weight: bold;"
        );
        addKizBtn.setOnAction(e -> {
            fileChooser.setTitle("Open PDF File");
            fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

            File file = fileChooser.showOpenDialog(null);
            if (file != null) {
                Task<List<String>> loadKizesTask = new Task<>() {
                    @Override
                    protected List<String> call() throws Exception {
                        return PdfDataMatrixReader.readDataMatrixFromPdf(file);
                    }
                };
                loadKizesTask.setOnRunning(ex -> {
                    loading.setVisible(true);
                });
                loadKizesTask.setOnSucceeded(ex -> {
                    loading.setVisible(false);
                    List<String> kizs = loadKizesTask.getValue();
                    int count = KizService.addKizs(selectedShop.getId(), category.getId(), kizs);
                    category.setCountKiz(category.getCountKiz() + count);
                    countKizsField.setText(String.valueOf(category.getCountKiz()));
                });
                loadKizesTask.setOnFailed(ex -> {
                    loading.setVisible(false);
                    showError(ex.getSource().getException().getMessage());
                });

                new Thread(loadKizesTask).start();
            }
        });

        Button deleteCategory = new Button("Del");
        deleteCategory.setStyle(
                "-fx-background-color: #d70202;" +
                        "-fx-text-fill: #e6e6e6;"
        );
        deleteCategory.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Xóa danh mục");
            alert.setHeaderText("Bạn chắc chắn muốn xóa danh mục " + category.getName() + " không?");

            ButtonType buttonTypeConfirm = new ButtonType("Xóa", ButtonBar.ButtonData.YES);
            ButtonType buttonTypeCancel =  new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.getButtonTypes().setAll(buttonTypeConfirm, buttonTypeCancel);

            Optional<ButtonType> result = alert.showAndWait();

            if (result.isPresent() && result.get() == buttonTypeConfirm) {
                KizService.deleteKizs(selectedShop.getId(), category.getId());
                loadCategories();
            }
        });

        hBox.getChildren().addAll(idLabel, nameLabel, countKizsField, addKizBtn, deleteCategory);

        return hBox;
    }


    public void onSettings(ActionEvent event) {

        Dialog<Integer> dialog = new Dialog<>();
        dialog.setTitle("Chọn kiểu in");

        int currentType = ConfigService.getPrintType();

        HBox root = new HBox(20);
        root.setPadding(new Insets(20));

        ImageView type1 = createPrintTypeView("type1.png", 1, currentType, dialog);
        ImageView type2 = createPrintTypeView("type2.png", 2, currentType, dialog);
        ImageView type3 = createPrintTypeView("type3.png", 3, currentType, dialog);

        root.getChildren().addAll(type1, type2, type3);

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK);

        dialog.showAndWait();
    }

    private ImageView createPrintTypeView(String imagePath, int type, int currentType, Dialog<Integer> dialog) {

        Image image = new Image(
                Objects.requireNonNull(getClass().getResource(imagePath)).toExternalForm()
        );

        ImageView imageView = new ImageView(image);

        imageView.setFitWidth(200);
        imageView.setFitHeight(400);
        imageView.setCursor(Cursor.HAND);

        if (type == currentType) {
            imageView.setEffect(createInnerShadow());
        }

        imageView.setOnMouseEntered(e -> {
            imageView.setScaleX(1.01);
            imageView.setScaleY(1.01);
        });

        imageView.setOnMouseExited(e -> {
            imageView.setScaleX(1);
            imageView.setScaleY(1);
        });

        imageView.setOnMouseClicked(e -> {
            ConfigService.updatePrintType(type);
            dialog.setResult(type);
        });

        return imageView;
    }

    private InnerShadow createInnerShadow() {
        InnerShadow innerShadow = new InnerShadow();
        innerShadow.setRadius(15.0);
        innerShadow.setColor(Color.MAGENTA);
        innerShadow.setChoke(0.5);
        return innerShadow;
    }

    public void onUpdateShop(MouseEvent mouseEvent) {
        Dialog<ButtonType> dialogUpdateShop = new Dialog<>();
        dialogUpdateShop.setTitle("Cập nhật cửa hàng");

        VBox vBox = new VBox(8);
        vBox.setPrefWidth(300);
        vBox.setPadding(new Insets(20));

        Label nameShopLabel = new Label("Tên cửa hàng *");
        TextField nameField = new TextField(selectedShop.getName());

        Label apiKeyShopLabel = new Label("API Key *");
        TextField apiKeyField = new TextField(selectedShop.getApiKey());

        vBox.getChildren().addAll(nameShopLabel, nameField, apiKeyShopLabel, apiKeyField);

        dialogUpdateShop.getDialogPane().setContent(vBox);

        ButtonType addBtn = new ButtonType("Lưu", ButtonType.OK.getButtonData());
        ButtonType cancelBtn = new ButtonType("Hủy", ButtonType.CANCEL.getButtonData());

        dialogUpdateShop.getDialogPane().getButtonTypes().addAll(cancelBtn,  addBtn);

        Optional<ButtonType> result = dialogUpdateShop.showAndWait();

        if (result.isPresent() && result.get() == addBtn) {
            String name = nameField.getText();
            String apiKey = apiKeyField.getText();

            if (!name.isBlank() && !apiKey.isBlank()) {
                Shop shop = new Shop(name.trim(), apiKey.trim());
                ShopService.updateShop(selectedShop.getId(), shop);
                selectedShop.setName(name);
                selectedShop.setApiKey(apiKey);
            }
        }
    }
}
