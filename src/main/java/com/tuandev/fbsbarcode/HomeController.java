package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.*;
import com.tuandev.fbsbarcode.services.*;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.effect.InnerShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.util.*;
import java.util.List;

public class HomeController implements Initializable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HomeController.class);
    private final OrderImportWorkflow orderImportWorkflow = new OrderImportWorkflow();
    private final OrderExportWorkflow orderExportWorkflow = new OrderExportWorkflow();
    private final ShopDialogService shopDialogService = new ShopDialogService();
    private final CategoryDialogService categoryDialogService = new CategoryDialogService();
    private final KizInventoryWorkflow kizInventoryWorkflow = new KizInventoryWorkflow();

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
    private Shop selectedShop;
    private HBox selectedBox;
    private FileChooser fileChooser;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        Database.initDatabase();

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
        AppTaskExecutor.execute(task);
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi hệ thống");
        alert.setHeaderText("Có lỗi xảy ra");
        alert.setContentText(message);
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
        shopDialogService.showCreateDialog().ifPresent(shop -> {
            int count = ShopService.addShop(shop);
            if (count > 0) {
                loadShops();
            }
        });
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
                        return orderImportWorkflow.importOrders(file, selectedShop);
                    }
                };
                loadOrdersTask.setOnRunning(e -> {
                    loading.setVisible(true);
                });
                loadOrdersTask.setOnFailed(e -> {
                    LOGGER.error("Không thể đọc file Excel {}", file.getAbsolutePath(), e.getSource().getException());
                    showError(e.getSource().getException().getMessage());
                    loading.setVisible(false);
                });
                loadOrdersTask.setOnSucceeded(e -> {
                    orders = loadOrdersTask.getValue();
                    loading.setVisible(false);
                    orderTable.setItems(FXCollections.observableArrayList(orders));
                    orderTable.refresh();
                    if (orders.isEmpty()) {
                        orderTable.getItems().clear();
                        showError("Không có đơn hàng hợp lệ trong file Excel");
                    }
                });
                AppTaskExecutor.execute(loadOrdersTask);
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

        // Export PDF
        fileChooser.setTitle("Open PDF File");
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            File orderDetailsFile = new File(
                    file.getParent(),
                    "NHAT_HANG-" + file.getName()
            );

            Task<OrderExportWorkflow.ExportResult> task = new Task<>() {
                @Override
                protected OrderExportWorkflow.ExportResult call() throws Exception {
                    return orderExportWorkflow.export(
                            new OrderExportWorkflow.ExportRequest(
                                    selectedShop,
                                    orders,
                                    kizCommand.getText(),
                                    ConfigService.getPrintType(),
                                    file,
                                    orderDetailsFile
                            )
                    );
                }
            };

            task.setOnRunning(e -> {
                loading.setVisible(true);
            });

            task.setOnFailed(e -> {
                loading.setVisible(false);

                Throwable ex = task.getException();
                LOGGER.error("Export thất bại cho shop {}", selectedShop.getId(), ex);
                showError(ex.getMessage());
            });

            task.setOnSucceeded(e -> {
                loading.setVisible(false);
                orders = task.getValue().exportedOrders();

                loadCategories();
                orderTable.setItems(FXCollections.observableArrayList(orders));
                orderTable.refresh();

                try {
                    Desktop.getDesktop().open(orderDetailsFile);
                    Desktop.getDesktop().open(file);
                } catch (IOException ex) {
                    LOGGER.error("Không thể mở file export", ex);
                }
            });

            AppTaskExecutor.execute(task);
        }

    }

    public void onAddCategory(ActionEvent actionEvent) {
        try {
            Optional<Category> categoryResult = categoryDialogService.showCreateDialog();
            if (categoryResult.isPresent()) {
                int rowCount = CategoryService.createCategory(categoryResult.get());
                if (rowCount > 0) {
                    loadCategories();
                }
            }
        } catch (NumberFormatException e) {
            showError("Id là số nguyên");
        } catch (SQLException e) {
            LOGGER.error("Không thể thêm category", e);
            showError("ID đã tồn tại! Vui lòng nhập ID khác");
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
        AppTaskExecutor.execute(loadCategoriesTask);
    }

    private HBox addCategoryItem(Category category) {
        FXMLLoader loader = FxmlViewLoader.loader(CategoryItemController.class, "category-item.fxml");
        HBox root = FxmlViewLoader.load(loader);
        CategoryItemController controller = loader.getController();
        controller.setCategory(category);
        controller.setOnAddKiz(() -> {
            fileChooser.setTitle("Open PDF File");
            fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

            File file = fileChooser.showOpenDialog(null);
            if (file != null) {
                Task<Integer> loadKizesTask = new Task<>() {
                    @Override
                    protected Integer call() throws Exception {
                        return kizInventoryWorkflow.importKizFromPdf(file, selectedShop, category);
                    }
                };
                loadKizesTask.setOnRunning(ex -> {
                    loading.setVisible(true);
                });
                loadKizesTask.setOnSucceeded(ex -> {
                    loading.setVisible(false);
                    int count = loadKizesTask.getValue();
                    category.setCountKiz(category.getCountKiz() + count);
                    controller.updateCount(category.getCountKiz());
                });
                loadKizesTask.setOnFailed(ex -> {
                    loading.setVisible(false);
                    showError(ex.getSource().getException().getMessage());
                });

                AppTaskExecutor.execute(loadKizesTask);
            }
        });
        controller.setOnDeleteCategory(() -> {
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
        return root;
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
        shopDialogService.showUpdateDialog(selectedShop).ifPresent(shop -> {
                ShopService.updateShop(selectedShop.getId(), shop);
                selectedShop.setName(shop.getName());
                selectedShop.setApiKey(shop.getApiKey());
                currentShopLabel.setText(shop.getName());
                loadShops();
        });
    }
}
