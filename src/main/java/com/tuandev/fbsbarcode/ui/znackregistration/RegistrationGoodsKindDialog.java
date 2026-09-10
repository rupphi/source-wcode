package com.tuandev.fbsbarcode.ui.znackregistration;

import com.tuandev.fbsbarcode.integration.znack.registration.RegistrationDraftPreparer.KindGroup;
import com.tuandev.fbsbarcode.integration.znack.registration.RegistrationDraftPreparer.Prepared;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.*;
import java.util.stream.Collectors;

/** Choices are scoped to this dialog/batch; no inferred or cross-shop mapping is saved. */
public final class RegistrationGoodsKindDialog {
    static Optional<List<Prepared>> choose(String shopName, List<Prepared> prepared) {
        if (prepared.stream().noneMatch(p -> p.draft() != null && p.missing().isEmpty() && p.kindGroup() != null))
            return Optional.of(prepared);
        return create(shopName, prepared).showAndWait();
    }

    public static Dialog<List<Prepared>> create(String shopName, List<Prepared> prepared) {
        var groups = prepared.stream().filter(p -> p.draft() != null && p.missing().isEmpty() && p.kindGroup() != null)
                .collect(Collectors.groupingBy(Prepared::kindGroup, LinkedHashMap::new, Collectors.toList()));
        var i18n = I18nService.getInstance();
        Dialog<List<Prepared>> dialog = new Dialog<>();
        dialog.setTitle(i18n.tr("znack.registration.kind_title"));
        dialog.setHeaderText(shopName + "\n" + i18n.tr("znack.registration.kind_hint"));
        dialog.setResizable(true);
        ButtonType proceed = new ButtonType(i18n.tr("znack.registration.kind_continue"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(proceed, ButtonType.CANCEL);
        VBox content = new VBox(12);
        Map<KindGroup, ComboBox<String>> choices = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            KindGroup group = entry.getKey();
            Label heading = new Label(group.subjectName() + " • " + entry.getValue().size() + " SKU\n"
                    + "TN VED: " + group.tnved() + " • Znack: " + group.categoryName());
            heading.setWrapText(true);
            ComboBox<String> choice = new ComboBox<>();
            choice.getItems().setAll(entry.getValue().getFirst().kindOptions());
            choice.setPromptText(i18n.tr("znack.registration.kind_choose"));
            choice.setAccessibleText(group.subjectName() + " — " + i18n.tr("znack.registration.kind_choose"));
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setDisable(choice.getItems().isEmpty());
            Button clear = new Button(i18n.tr("znack.registration.kind_skip"));
            clear.setOnAction(event -> choice.setValue(null));
            Label members = new Label(entry.getValue().stream().map(p -> p.sku().vendorCode() + " / "
                    + p.sku().size() + " — " + p.sku().title()).collect(Collectors.joining("\n")));
            members.setWrapText(true);
            TitledPane details = new TitledPane(i18n.tr("znack.registration.kind_products"), members);
            details.setExpanded(false);
            VBox row = new VBox(6, heading, choice, clear, details);
            if (choice.getItems().isEmpty()) {
                Label unavailable = new Label(i18n.tr("znack.registration.kind_empty"));
                unavailable.setWrapText(true); row.getChildren().add(unavailable);
            }
            content.getChildren().add(row); choices.put(group, choice);
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true); scroll.setPrefViewportWidth(620); scroll.setPrefViewportHeight(380);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(button -> {
            if (button != proceed) return null;
            List<Prepared> result = new ArrayList<>();
            for (Prepared item : prepared) {
                var choice = choices.get(item.kindGroup());
                if (item.draft() == null || !item.missing().isEmpty() || item.kindGroup() == null) result.add(item);
                else if (choice != null && choice.getValue() != null) result.add(item.chooseKind(choice.getValue()));
            }
            return List.copyOf(result);
        });
        return dialog;
    }
}
