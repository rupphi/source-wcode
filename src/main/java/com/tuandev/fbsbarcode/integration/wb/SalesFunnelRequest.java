package com.tuandev.fbsbarcode.integration.wb;

import java.time.LocalDate;
import java.util.List;

public class SalesFunnelRequest {
    private Period selectedPeriod;
    private Period pastPeriod;
    private List<Long> nmIds = List.of();
    private List<String> brandNames = List.of();
    private List<Integer> subjectIds = List.of();
    private List<Integer> tagIds = List.of();
    private boolean skipDeletedNm = true;
    private OrderBy orderBy;
    private int limit = 100;
    private int offset = 0;

    public static SalesFunnelRequest lastSevenDays(LocalDate today, boolean includeOrderBy) {
        SalesFunnelRequest request = new SalesFunnelRequest();
        request.selectedPeriod = new Period(today.minusDays(7).toString(), today.toString());
        request.pastPeriod = new Period(today.minusDays(14).toString(), today.minusDays(7).toString());
        request.orderBy = includeOrderBy ? new OrderBy("orders", "desc") : null;
        return request;
    }

    public Period getSelectedPeriod() {
        return selectedPeriod;
    }

    public void setOrderBy(OrderBy orderBy) {
        this.orderBy = orderBy;
    }

    public static class Period {
        private String start;
        private String end;

        public Period() {
        }

        public Period(String start, String end) {
            this.start = start;
            this.end = end;
        }

        public String getStart() {
            return start;
        }

        public String getEnd() {
            return end;
        }
    }

    public static class OrderBy {
        private String field;
        private String mode;

        public OrderBy() {
        }

        public OrderBy(String field, String mode) {
            this.field = field;
            this.mode = mode;
        }
    }
}
