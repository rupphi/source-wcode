package com.tuandev.fbsbarcode.features.dashboard;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.integration.wb.SalesFunnelResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesFunnelAnalyzerTest {
    private final Gson gson = new Gson();
    private final SalesFunnelAnalyzer analyzer = new SalesFunnelAnalyzer();

    @Test
    void shouldParseAndSortTopSellingByOrdersThenRevenue() {
        SalesFunnelResponse response = gson.fromJson("""
                {
                  "data": [
                    {"product": {"nmID": 1001, "name": "A"}, "selectedPeriod": {"orderCount": 3, "orderSum": 300}},
                    {"product": {"nmID": 1002, "name": "B"}, "selectedPeriod": {"orderCount": 8, "orderSum": 100}},
                    {"product": {"nmID": 1003, "name": "C"}, "selectedPeriod": {"orderCount": 8, "orderSum": 900}}
                  ]
                }
                """, SalesFunnelResponse.class);

        var top = analyzer.topSelling(response.getItems(), Map.of());

        assertEquals(3, top.size());
        assertEquals(1003, top.get(0).nmId());
        assertEquals(1002, top.get(1).nmId());
        assertEquals(1001, top.get(2).nmId());
    }

    @Test
    void shouldSelectPotentialProductsWhenFourOfFiveGroupsMatch() {
        SalesFunnelResponse response = gson.fromJson("""
                {
                  "data": [
                    {
                      "product": {"nmID": 2001, "name": "Potential", "productRating": 4.6, "feedbackRating": 4.4},
                      "selectedPeriod": {
                        "openCount": 50,
                        "cartCount": 12,
                        "orderCount": 6,
                        "addToWishlist": 3,
                        "cancelCount": 1,
                        "conversions": {"addToCartPercent": 24, "cartToOrderPercent": 50},
                        "stocks": {"balanceSum": 4}
                      }
                    }
                  ]
                }
                """, SalesFunnelResponse.class);

        var potential = analyzer.potentialProducts(response.getItems(), Map.of());

        assertEquals(1, potential.size());
        assertEquals(5, potential.get(0).score());
        assertEquals(2001, potential.get(0).nmId());
    }

    @Test
    void shouldNotCrashWhenFieldsAreMissing() {
        SalesFunnelResponse response = gson.fromJson("""
                {"data": [{"product": {"nmID": 3001}}]}
                """, SalesFunnelResponse.class);

        assertDoesNotThrow(() -> analyzer.topSelling(response.getItems(), Map.of()));
        assertDoesNotThrow(() -> analyzer.potentialProducts(response.getItems(), Map.of()));
    }

    @Test
    void shouldParseDataObjectWithProductsList() {
        SalesFunnelResponse response = gson.fromJson("""
                {"data": {"products": [{"product": {"nmID": 4001}, "selectedPeriod": {"orderCount": 2}}]}}
                """, SalesFunnelResponse.class);

        assertEquals(1, response.getItems().size());
        assertEquals(4001, analyzer.topSelling(response.getItems(), Map.of()).get(0).nmId());
    }

    @Test
    void shouldUseStatisticLevelConversionsAndStocksWhenSelectedPeriodOmitsThem() {
        SalesFunnelResponse response = gson.fromJson("""
                {
                  "data": [{
                    "product": {"nmID": 5001, "productRating": 4.3, "feedbackRating": 4.2},
                    "statistic": {
                      "selectedPeriod": {"openCount": 20, "cartCount": 5, "orderCount": 2, "cancelCount": 0},
                      "conversions": {"addToCartPercent": 25, "cartToOrderPercent": 40},
                      "stocks": {"balanceSum": 3}
                    }
                  }]
                }
                """, SalesFunnelResponse.class);

        var potential = analyzer.potentialProducts(response.getItems(), Map.of());

        assertEquals(1, potential.size());
        assertEquals(25.0, potential.get(0).addToCartPercent());
        assertEquals(3, potential.get(0).stock());
    }
}
