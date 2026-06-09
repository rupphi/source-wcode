package com.tuandev.fbsbarcode.models;

public class Category {
    private int id;
    private String name;
    private int countKiz;
    private int displayId;

    public Category() {
    }

    public Category(int id, String name) {
        this.id = id;
        this.name = name;
        this.displayId = id;
    }

    public Category(int id, String name, int countKiz) {
        this.id = id;
        this.name = name;
        this.countKiz = countKiz;
        this.displayId = id;
    }

    public Category(int id, String name, int countKiz, int displayId) {
        this.id = id;
        this.name = name;
        this.countKiz = countKiz;
        this.displayId = displayId;
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

    public int getCountKiz() {
        return countKiz;
    }

    public void setCountKiz(int countKiz) {
        this.countKiz = countKiz;
    }

    public int getDisplayId() {
        return displayId > 0 ? displayId : id;
    }

    public void setDisplayId(int displayId) {
        this.displayId = displayId;
    }

    @Override
    public String toString() {
        return "Category{" +
                "id=" + id +
                ", displayId=" + getDisplayId() +
                ", name='" + name + '\'' +
                ", countKiz=" + countKiz +
                '}';
    }
}
