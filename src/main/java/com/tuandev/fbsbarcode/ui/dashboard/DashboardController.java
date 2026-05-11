package com.tuandev.fbsbarcode.ui.dashboard;

import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.models.Order;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DashboardController {

    @FXML
    private Label totalOrdersLabel;

    @FXML
    private Label openSuppliesLabel;

    @FXML
    private Label syncStatusLabel;

    @FXML
    private PieChart orderStatusChart;

    public void updateData(List<Order> orders, List<WbSupplySummary> supplies, boolean isSyncing) {
        Platform.runLater(() -> {
            totalOrdersLabel.setText(String.valueOf(orders.size()));
            long openSupplies = supplies.stream().filter(s -> !s.isDone()).count();
            openSuppliesLabel.setText(String.valueOf(openSupplies));
            syncStatusLabel.setText(isSyncing ? "Đang đồng bộ..." : "Hoàn tất");
            updateChart(orders);
        });
    }

    private void updateChart(List<Order> orders) {
        if (orders.isEmpty()) {
            orderStatusChart.setData(FXCollections.emptyObservableList());
            return;
        }

        long hasKiz = orders.stream().filter(o -> o.getKiz() != null && !o.getKiz().isBlank()).count();
        long noKiz = orders.size() - hasKiz;

        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        if (hasKiz > 0) {
            pieChartData.add(new PieChart.Data("Đã gắn KIZ (" + hasKiz + ")", hasKiz));
        }
        if (noKiz > 0) {
            pieChartData.add(new PieChart.Data("Chưa có KIZ (" + noKiz + ")", noKiz));
        }

        orderStatusChart.setData(pieChartData);
    }
}
