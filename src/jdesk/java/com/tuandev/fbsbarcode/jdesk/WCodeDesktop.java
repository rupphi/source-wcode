package com.tuandev.fbsbarcode.jdesk;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyCommandService;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.wildberries.WildberriesCommandService;
import com.tuandev.fbsbarcode.jdesk.wildberries.WildberriesCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.workspace.JDeskCommands;
import com.tuandev.fbsbarcode.jdesk.workspace.WorkspaceCommandService;
import com.tuandev.fbsbarcode.jdesk.workspace.WorkspaceCommandServiceCommands;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.Csp;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;
import java.util.Arrays;

public final class WCodeDesktop {
    private WCodeDesktop() {
    }

    public static void main(String[] args) {
        int exitCode = 1;
        try (JDeskStartup.Session ignored =
                JDeskStartup.prepare(AppPaths.appDataDir(), BuildConfig.getAppVersion())) {
            boolean smokeTest = Arrays.asList(args).contains("--jdesk-smoke");
            WorkspaceCommandService workspace = new WorkspaceCommandService();
            WildberriesCommandService wildberries = new WildberriesCommandService();
            SupplyCommandService supplies = new SupplyCommandService();
            JDeskApplication.Builder application = JDeskApplication.builder()
                    .id("com.tuandev.wcode")
                    .commands(JDeskCommands.combine(
                            WorkspaceCommandServiceCommands.create(workspace),
                            WildberriesCommandServiceCommands.create(wildberries),
                            SupplyCommandServiceCommands.create(supplies)))
                    .capabilities(Capabilities.fromResource("jdesk-capabilities.json"))
                    .contentSecurityPolicy(Csp.defaults())
                    .window(WindowConfig.builder()
                            .id("main")
                            .title("WCode")
                            .size(1440, 900)
                            .minSize(960, 640)
                            .rememberBounds(true)
                            .entry("jdesk://app/index.html")
                            .build())
                    .lifecycle(new LifecycleListener() {
                        @Override
                        public void onReady(ApplicationHandle handle) {
                            if (smokeTest) {
                                handle.requestStop();
                            }
                        }
                    });

            String devUrl = System.getProperty("jdesk.devUrl");
            if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
                application.devServerUrl(devUrl);
            }
            exitCode = application.run(args);
        } catch (AppDataLock.AlreadyRunningException exception) {
            System.err.println("WCode is already running for this app-data directory.");
        } catch (Exception exception) {
            System.err.println("WCode could not start safely. No data migration was continued.");
        }
        System.exit(exitCode);
    }
}
