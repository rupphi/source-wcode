package com.tuandev.fbsbarcode.jdesk;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.jdesk.fbo.FboCatalogCommandService;
import com.tuandev.fbsbarcode.jdesk.fbo.FboCatalogCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.fbo.FboPrintCommandService;
import com.tuandev.fbsbarcode.jdesk.fbo.FboPrintCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.kizmapping.KizMappingCommandService;
import com.tuandev.fbsbarcode.jdesk.kizmapping.KizMappingCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.order.ExcelOrderImportCommandService;
import com.tuandev.fbsbarcode.jdesk.order.ExcelOrderImportCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.packing.PackingCommandService;
import com.tuandev.fbsbarcode.jdesk.packing.PackingCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.print.PrintCommandService;
import com.tuandev.fbsbarcode.jdesk.print.PrintCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.print.PrintExportCommandService;
import com.tuandev.fbsbarcode.jdesk.print.PrintExportCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.print.PrintHistoryCommandService;
import com.tuandev.fbsbarcode.jdesk.print.PrintHistoryCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.print.PrintHistoryReprintCommandService;
import com.tuandev.fbsbarcode.jdesk.print.PrintHistoryReprintCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyCommandService;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyDetailCommandService;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyDetailCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyRefreshCommandService;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyRefreshCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.template.TemplateDesignerCommandService;
import com.tuandev.fbsbarcode.jdesk.template.TemplateDesignerCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.template.TemplateDesignerMutationCommandService;
import com.tuandev.fbsbarcode.jdesk.template.TemplateDesignerMutationCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.wildberries.WildberriesCommandService;
import com.tuandev.fbsbarcode.jdesk.wildberries.WildberriesCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.workspace.JDeskCommands;
import com.tuandev.fbsbarcode.jdesk.workspace.WorkspaceCommandService;
import com.tuandev.fbsbarcode.jdesk.workspace.WorkspaceCommandServiceCommands;
import com.tuandev.fbsbarcode.jdesk.znack.ZnackCommandService;
import com.tuandev.fbsbarcode.jdesk.znack.ZnackCommandServiceCommands;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.Csp;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;
import java.time.Duration;
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
            OrderImageAssetService orderImages = new OrderImageAssetService();
            FboCatalogCommandService fboCatalog = new FboCatalogCommandService(orderImages);
            FboPrintCommandService fboPrinting = new FboPrintCommandService();
            KizMappingCommandService kizMappings = new KizMappingCommandService();
            ZnackCommandService znack = new ZnackCommandService();
            PackingCommandService packing = new PackingCommandService(orderImages);
            ExcelOrderImportCommandService excelOrders = new ExcelOrderImportCommandService(orderImages);
            PrintCommandService printing = new PrintCommandService();
            PrintHistoryCommandService printHistory = new PrintHistoryCommandService();
            PrintHistoryReprintCommandService historyReprint = new PrintHistoryReprintCommandService();
            PrintExportCommandService printExport = new PrintExportCommandService();
            TemplateDesignerCommandService templates = new TemplateDesignerCommandService();
            TemplateDesignerMutationCommandService templateMutations =
                    new TemplateDesignerMutationCommandService();
            var printExportCommands = CommandTimeoutOverrides.withTimeout(
                    PrintExportCommandServiceCommands.create(printExport),
                    "printing.exportSupply",
                    Duration.ofMinutes(10));
            var historyReprintCommands = CommandTimeoutOverrides.withTimeout(
                    PrintHistoryReprintCommandServiceCommands.create(historyReprint),
                    "printing.reprintHistory",
                    Duration.ofMinutes(10));
            var fboPrintCommands = CommandTimeoutOverrides.withTimeout(
                    FboPrintCommandServiceCommands.create(fboPrinting),
                    "fbo.export",
                    Duration.ofMinutes(10));
            SupplyDetailCommandService supplyDetails = new SupplyDetailCommandService(orderImages);
            SupplyRefreshCommandService supplyRefresh = new SupplyRefreshCommandService();
            JDeskApplication.Builder application = JDeskApplication.builder()
                    .id("com.tuandev.wcode")
                    .commands(JDeskCommands.combine(
                            WorkspaceCommandServiceCommands.create(workspace),
                            WildberriesCommandServiceCommands.create(wildberries),
                            SupplyCommandServiceCommands.create(supplies),
                            PackingCommandServiceCommands.create(packing),
                            FboCatalogCommandServiceCommands.create(fboCatalog),
                            fboPrintCommands,
                            KizMappingCommandServiceCommands.create(kizMappings),
                            ZnackCommandServiceCommands.create(znack),
                            SupplyDetailCommandServiceCommands.create(supplyDetails),
                            SupplyRefreshCommandServiceCommands.create(supplyRefresh),
                            ExcelOrderImportCommandServiceCommands.create(excelOrders),
                            PrintCommandServiceCommands.create(printing),
                            PrintHistoryCommandServiceCommands.create(printHistory),
                            historyReprintCommands,
                            printExportCommands,
                            TemplateDesignerCommandServiceCommands.create(templates),
                            TemplateDesignerMutationCommandServiceCommands.create(templateMutations)))
                    .capabilities(Capabilities.fromResource("jdesk-capabilities.json"))
                    .contentSecurityPolicy(Csp.defaults())
                    .assetRoute("order-images", orderImages)
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
