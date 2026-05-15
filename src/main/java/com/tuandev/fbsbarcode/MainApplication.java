package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;


import java.io.IOException;

public class MainApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApplication.class.getResource("/com/tuandev/fbsbarcode/ui/workspace/home-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load());
        scene.getStylesheets().add(MainApplication.class.getResource("/com/tuandev/fbsbarcode/styles/theme.css").toExternalForm());
        stage.setTitle("WCode v" + BuildConfig.getAppVersion() + " (Zalo: 0335407670)");

        Image appIcon = new Image(MainApplication.class.getResourceAsStream("/com/tuandev/fbsbarcode/assets/images/logo.png"));
        stage.getIcons().add(appIcon);
        stage.setOnCloseRequest(event -> {
            boolean hasBackgroundWork = AppTaskExecutor.hasRunningTasks() || KizAttachmentCoordinator.getInstance().hasActiveJobs();
            if (hasBackgroundWork) {
                event.consume();
                AlertService.showWarning(
                        "Ứng dụng đang xử lý",
                        "Không thể thoát ứng dụng lúc này",
                        "Hệ thống vẫn đang đồng bộ dữ liệu hoặc gửi KIZ lên Wildberries ở nền. Vui lòng chờ tiến trình hoàn tất rồi mới tắt app."
                );
            }
        });

        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        AppTaskExecutor.shutdown();
    }
}
