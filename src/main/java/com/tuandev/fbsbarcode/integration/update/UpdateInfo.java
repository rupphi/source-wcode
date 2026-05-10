package com.tuandev.fbsbarcode.integration.update;

import com.google.gson.annotations.SerializedName;
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
        }
        return downloadUrls != null && !downloadUrls.isEmpty()
                ? downloadUrls.values().iterator().next()
                : null;
    }
}
