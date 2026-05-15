package com.tuandev.fbsbarcode.integration.update;

import java.util.Map;

public class UpdateInfo {

    private String version;
    private String releaseDate;
    private String changelog;
    private Map<String, String> downloadUrls;
    private boolean mandatory;

    public String getVersion() { return version; }
    public String getReleaseDate() { return releaseDate; }
    public String getChangelog() { return changelog; }
    public Map<String, String> getDownloadUrls() { return downloadUrls; }
    public boolean isMandatory() { return mandatory; }

    public void setVersion(String version) { this.version = version; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public void setDownloadUrls(Map<String, String> downloadUrls) { this.downloadUrls = downloadUrls; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }

    public String getBestDownloadUrl() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") && downloadUrls != null) {
            String exe = downloadUrls.get("exe");
            if (exe != null && !exe.isBlank()) return exe;
            String msi = downloadUrls.get("msi");
            if (msi != null && !msi.isBlank()) return msi;
        }
        return downloadUrls != null && !downloadUrls.isEmpty()
                ? downloadUrls.values().iterator().next()
                : null;
    }

    public String getDisplayChangelog() {
        if (changelog == null || changelog.isBlank()) {
            return com.tuandev.fbsbarcode.shared.I18nService.getInstance().tr("update.dialog.no_changelog");
        }
        return changelog.replace("\r\n", "\n").trim();
    }
}
