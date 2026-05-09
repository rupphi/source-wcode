package com.tuandev.fbsbarcode.services;

import java.util.ArrayList;
import java.util.List;

public final class KizCommandParser {
    private KizCommandParser() {
    }

    public static List<KizRange> parse(String commandText) {
        List<KizRange> ranges = new ArrayList<>();
        if (commandText == null || commandText.isBlank()) {
            return ranges;
        }

        String[] commands = commandText.split("\\R");
        for (String commandLine : commands) {
            if (commandLine == null || commandLine.isBlank()) {
                continue;
            }

            String[] idAndRange = commandLine.trim().split(":");
            if (idAndRange.length != 2) {
                throw new IllegalArgumentException("Sai định dạng: " + commandLine + " | Đúng: ID:FROM-TO");
            }

            int categoryId;
            int from;
            int to;
            try {
                categoryId = Integer.parseInt(idAndRange[0].trim());
                String[] fromTo = idAndRange[1].trim().split("-");
                if (fromTo.length != 2) {
                    throw new IllegalArgumentException("Sai khoảng FROM-TO: " + commandLine);
                }

                from = Integer.parseInt(fromTo[0].trim());
                to = Integer.parseInt(fromTo[1].trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Yêu cầu lấy KIZ chưa đúng. Định dạng đúng: ID:FROM-TO");
            }

            if (from < 1 || to < 1 || from > to) {
                throw new IllegalArgumentException("Khoảng KIZ không hợp lệ: " + commandLine);
            }

            ranges.add(new KizRange(categoryId, from, to, commandLine.trim()));
        }

        return ranges;
    }

    public record KizRange(int categoryId, int from, int to, String rawLine) {
        public int count() {
            return to - from + 1;
        }
    }
}
