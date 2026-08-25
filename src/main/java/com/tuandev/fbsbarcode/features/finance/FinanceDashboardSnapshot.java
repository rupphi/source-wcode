package com.tuandev.fbsbarcode.features.finance;

import java.util.List;

public record FinanceDashboardSnapshot(
        List<FinanceDaily> days,
        double grossSales,
        double returnsAmount,
        double netPayout,
        double penaltyCost,
        double logisticsCost,
        double storageCost,
        double otherCost,
        double advertisingCost,
        double netProfit
) {
    public static FinanceDashboardSnapshot fromDays(List<FinanceDaily> days) {
        List<FinanceDaily> safeDays = List.copyOf(days);
        double gross = safeDays.stream().mapToDouble(FinanceDaily::grossSales).sum();
        double returns = safeDays.stream().mapToDouble(FinanceDaily::returnsAmount).sum();
        double payout = safeDays.stream().mapToDouble(FinanceDaily::netPayout).sum();
        double penalty = safeDays.stream().mapToDouble(FinanceDaily::penaltyCost).sum();
        double logistics = safeDays.stream().mapToDouble(FinanceDaily::logisticsCost).sum();
        double storage = safeDays.stream().mapToDouble(FinanceDaily::storageCost).sum();
        double other = safeDays.stream().mapToDouble(FinanceDaily::otherCost).sum();
        double additional = safeDays.stream().mapToDouble(FinanceDaily::additionalPayment).sum();
        double advertising = safeDays.stream().mapToDouble(FinanceDaily::advertisingCost).sum();
        return new FinanceDashboardSnapshot(safeDays, gross, returns, payout, penalty, logistics, storage,
                other, advertising, payout - penalty - logistics - storage - other + additional - advertising);
    }
}
