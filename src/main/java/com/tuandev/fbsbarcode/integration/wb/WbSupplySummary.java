package com.tuandev.fbsbarcode.integration.wb;

public class WbSupplySummary {
    private final String supplyId;
    private final String name;
    private final boolean done;
    private final Boolean b2b;
    private final String createdAt;

    public WbSupplySummary(String supplyId, String name, boolean done, Boolean b2b, String createdAt) {
        this.supplyId = supplyId;
        this.name = name;
        this.done = done;
        this.b2b = b2b;
        this.createdAt = createdAt;
    }

    public String getSupplyId() {
        return supplyId;
    }

    public String getName() {
        return name;
    }

    public boolean isDone() {
        return done;
    }

    public Boolean getB2b() {
        return b2b;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        String status = done ? "Đã giao" : "Đang xử lý";
        return supplyId + " - " + name + " (" + status + ")";
    }
}
