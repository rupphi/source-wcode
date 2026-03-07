package com.tuandev.fbsbarcode.models;

public class Category {
    private int id;
    private String name;
    private int countKiz;

    public Category() {
    }

    public Category(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public Category(int id, String name, int countKiz) {
        this.id = id;
        this.name = name;
        this.countKiz = countKiz;
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

    @Override
    public String toString() {
        return "Category{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", countKiz=" + countKiz +
                '}';
    }
}
