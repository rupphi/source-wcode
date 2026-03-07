package com.tuandev.fbsbarcode.models;

public class Kiz {
    private int id;
    private String code;
    private int category_id;
    private int shop_id;

    public Kiz() {
    }

    public Kiz(int id, String code) {
        this.id = id;
        this.code = code;
    }

    public Kiz(int id, String code, int category_id, int shop_id) {
        this.id = id;
        this.code = code;
        this.category_id = category_id;
        this.shop_id = shop_id;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getCategory_id() {
        return category_id;
    }

    public void setCategory_id(int category_id) {
        this.category_id = category_id;
    }

    public int getShop_id() {
        return shop_id;
    }

    public void setShop_id(int shop_id) {
        this.shop_id = shop_id;
    }

    @Override
    public String toString() {
        return "Kiz{" +
                "id=" + id +
                ", code='" + code + '\'' +
                ", category_id=" + category_id +
                ", shop_id=" + shop_id +
                '}';
    }
}
