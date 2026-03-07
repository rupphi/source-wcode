package com.tuandev.fbsbarcode.models;

public class Shop {
    private int id;
    private String name;
    private String apiKey;

    public Shop() {
    }

    public Shop(String name, String apiKey) {
        this.name = name;
        this.apiKey = apiKey;
    }

    public Shop(int id, String name, String apiKey) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String toString() {
        return "Shop{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", apiKey='" + apiKey + '\'' +
                '}';
    }
}
