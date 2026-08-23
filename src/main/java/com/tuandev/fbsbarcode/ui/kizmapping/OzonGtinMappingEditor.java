package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.integration.ozon.OzonCatalogRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductGtinMappingRepository;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import java.text.MessageFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javafx.concurrent.Task;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Maps Ozon catalog SKUs to a Znack GTIN without applying Wildberries category rules. */
final class OzonGtinMappingEditor {
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final OzonProductGtinMappingRepository mappings = new OzonProductGtinMappingRepository();

    void open(int shopId, String gtin, KizGtinMappingEditor.Host host) {
        Task<Data> task = new Task<>() {
            @Override
            protected Data call() {
                return new Data(catalog.findAll(shopId), mappings.findAll(shopId));
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            if (host.isCurrent()) openDialog(shopId, gtin, task.getValue(), host);
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void openDialog(int shopId, String gtin, Data data, KizGtinMappingEditor.Host host) {
        Dialog<List<String>> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("ozon.mapping.dialog.title"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        TextField search = new TextField();
        search.setPromptText(tr("ozon.mapping.search"));
        Label selectedLabel = new Label();
        selectedLabel.getStyleClass().add("text-muted");
        VBox rows = new VBox(6);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(480);
        Set<String> selected = data.currentMappings().entrySet().stream()
                .filter(entry -> gtin.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Runnable refresh = () -> renderRows(gtin, data, search.getText(), selected, rows, selectedLabel);
        search.textProperty().addListener((ignored, oldValue, newValue) -> refresh.run());
        VBox content = new VBox(10,
                new Label(tr("ozon.mapping.catalog_heading") + " - " + gtin),
                search,
                selectedLabel,
                scroll);
        content.setPrefSize(760, 560);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save ? List.copyOf(selected) : null);
        refresh.run();
        dialog.showAndWait().ifPresent(skus -> save(shopId, gtin, skus, host));
    }

    private void renderRows(
            String gtin,
            Data data,
            String query,
            Set<String> selected,
            VBox rows,
            Label selectedLabel) {
        rows.getChildren().clear();
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<OzonProductDto> visible = data.products().stream()
                .filter(product -> !product.archived() && !product.sku().isBlank())
                .filter(product -> normalizedQuery.isBlank() || matches(product, normalizedQuery))
                .toList();
        for (OzonProductDto product : visible) {
            CheckBox check = new CheckBox();
            check.setSelected(selected.contains(product.sku()));
            check.setOnAction(event -> {
                if (check.isSelected()) selected.add(product.sku());
                else selected.remove(product.sku());
                selectedLabel.setText(MessageFormat.format(tr("ozon.mapping.selected"), selected.size()));
            });
            Label name = new Label(product.name().isBlank() ? product.sku() : product.name());
            name.getStyleClass().add("text-strong");
            name.setWrapText(true);
            String metadata = "SKU " + product.sku()
                    + (product.offerId().isBlank() ? "" : "  -  Offer " + product.offerId());
            String owner = data.currentMappings().get(product.sku());
            if (owner != null && !owner.equals(gtin)) {
                metadata += "  -  " + MessageFormat.format(tr("ozon.mapping.current_gtin"), owner);
            }
            Label details = new Label(metadata);
            details.getStyleClass().add("text-muted");
            details.setWrapText(true);
            VBox labels = new VBox(3, name, details);
            HBox row = new HBox(10, check, labels, new Pane());
            HBox.setHgrow(labels, Priority.ALWAYS);
            row.getStyleClass().add("surface");
            labels.setOnMouseClicked(event -> {
                check.setSelected(!check.isSelected());
                if (check.isSelected()) selected.add(product.sku());
                else selected.remove(product.sku());
                selectedLabel.setText(MessageFormat.format(tr("ozon.mapping.selected"), selected.size()));
            });
            rows.getChildren().add(row);
        }
        if (visible.isEmpty()) {
            Label empty = new Label(tr("ozon.mapping.empty"));
            empty.getStyleClass().add("text-muted");
            empty.setWrapText(true);
            rows.getChildren().add(empty);
        }
        selectedLabel.setText(MessageFormat.format(tr("ozon.mapping.selected"), selected.size()));
    }

    private void save(int shopId, String gtin, List<String> skus, KizGtinMappingEditor.Host host) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                mappings.replaceForGtin(shopId, gtin, skus);
                return null;
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            host.saved();
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private static boolean matches(OzonProductDto product, String query) {
        return product.name().toLowerCase(Locale.ROOT).contains(query)
                || product.sku().toLowerCase(Locale.ROOT).contains(query)
                || product.offerId().toLowerCase(Locale.ROOT).contains(query);
    }

    private static String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    private record Data(List<OzonProductDto> products, Map<String, String> currentMappings) {
    }
}
