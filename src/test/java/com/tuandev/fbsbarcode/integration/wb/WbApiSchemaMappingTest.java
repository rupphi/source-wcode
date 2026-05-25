package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbApiSchemaMappingTest {
    private static final Gson GSON = new Gson();

    @Test
    void shouldDeserializeProductCardsResponse() {
        String json = """
                {
                  "cards": [
                    {
                      "nmID": 12345678,
                      "imtID": 123654789,
                      "nmUUID": "uuid-1",
                      "subjectID": 7771,
                      "subjectName": "AKF systems",
                      "vendorCode": "wb7f6mumjr1",
                      "kizMarked": true,
                      "needKiz": false,
                      "brand": "Test",
                      "title": "Product title",
                      "description": "Description",
                      "photos": [{"big":"big","c246x328":"c246","c516x688":"c516","square":"square","tm":"tm"}],
                      "video": "video-url",
                      "wholesale": {"enabled": true, "quantum": 112},
                      "dimensions": {"length": 55, "width": 40, "height": 15, "weightBrutto": 6.24, "isValid": false},
                      "characteristics": [{"id": 14177449, "name": "Color", "value": ["red"]}],
                      "sizes": [{"chrtID": 316399238, "techSize": "0", "skus": ["987456321654"]}],
                      "tags": [{"id": 592569, "name": "Popular", "color": "D1CFD7"}],
                      "createdAt": "2023-12-06T11:17:00.96577Z",
                      "updatedAt": "2023-12-06T11:17:00.96577Z"
                    }
                  ],
                  "cursor": {
                    "updatedAt": "2023-12-06T11:17:00.96577Z",
                    "nmID": 123654123,
                    "total": 1
                  }
                }
                """;

        WbProductCardsResponse response = GSON.fromJson(json, WbProductCardsResponse.class);

        assertEquals(1, response.getCards().size());
        assertEquals(12345678L, response.getCards().getFirst().getNmID());
        assertTrue(response.getCards().getFirst().getKizMarked());
        assertFalse(response.getCards().getFirst().getNeedKiz());
        assertEquals("987456321654", response.getCards().getFirst().getSizes().getFirst().getSkus().getFirst());
        assertEquals(1, response.getCursor().getTotal());
    }

    @Test
    void shouldDeserializeOrdersResponse() {
        String json = """
                {
                  "orders": [
                    {
                      "address": {"fullAddress":"Address 1","longitude":44.519068,"latitude":40.20192},
                      "ddate":"17.05.2024",
                      "sellerDate":"02.06.2025",
                      "salePrice":504600,
                      "requiredMeta":["uin"],
                      "optionalMeta":["sgtin"],
                      "deliveryType":"fbs",
                      "comment":"comment",
                      "scanPrice":1500,
                      "orderUid":"uid-1",
                      "article":"one-ring-7548",
                      "colorCode":"RAL 3017",
                      "rid":"rid-1",
                      "createdAt":"2022-05-04T07:56:29Z",
                      "offices":["Kaluga"],
                      "skus":["6665956397512"],
                      "id":13833711,
                      "warehouseId":658434,
                      "officeId":123,
                      "nmId":12345678,
                      "chrtId":987654321,
                      "price":1014,
                      "finalPrice":1014,
                      "convertedPrice":28322,
                      "convertedFinalPrice":1014,
                      "currencyCode":933,
                      "convertedCurrencyCode":643,
                      "cargoType":1,
                      "crossBorderType":1,
                      "isZeroOrder":false,
                      "options":{"isB2B":true},
                      "supplyId":"WB-GI-92937123"
                    }
                  ]
                }
                """;

        WbOrdersResponse response = GSON.fromJson(json, WbOrdersResponse.class);

        assertEquals(1, response.getOrders().size());
        assertEquals("WB-GI-92937123", response.getOrders().getFirst().getSupplyId());
        assertEquals("uin", response.getOrders().getFirst().getRequiredMeta().getFirst());
        assertEquals(12345678L, response.getOrders().getFirst().getNmId());
        assertTrue(response.getOrders().getFirst().getOptions().getIsB2B());
    }

    @Test
    void shouldSerializeSalesFunnelProductsRequestForV3Schema() {
        SalesFunnelRequest request = SalesFunnelRequest.lastSevenDays(LocalDate.of(2026, 5, 25), true);

        JsonObject json = JsonParser.parseString(GSON.toJson(request)).getAsJsonObject();

        assertEquals("2026-05-18", json.getAsJsonObject("selectedPeriod").get("start").getAsString());
        assertEquals("2026-05-24", json.getAsJsonObject("selectedPeriod").get("end").getAsString());
        assertEquals("2026-05-11", json.getAsJsonObject("pastPeriod").get("start").getAsString());
        assertEquals("2026-05-17", json.getAsJsonObject("pastPeriod").get("end").getAsString());
        assertTrue(json.getAsJsonArray("nmIds").isEmpty());
        assertTrue(json.getAsJsonArray("brandNames").isEmpty());
        assertTrue(json.getAsJsonArray("subjectIds").isEmpty());
        assertTrue(json.getAsJsonArray("tagIds").isEmpty());
        assertTrue(json.get("skipDeletedNm").getAsBoolean());
        assertEquals("openCard", json.getAsJsonObject("orderBy").get("field").getAsString());
        assertEquals("desc", json.getAsJsonObject("orderBy").get("mode").getAsString());
        assertEquals(1000, json.get("limit").getAsInt());
        assertEquals(0, json.get("offset").getAsInt());
    }

    @Test
    void shouldDeserializeSalesFunnelProductsV3Response() {
        String json = """
                {
                  "data": {
                    "products": [
                      {
                        "product": {
                          "nmId": 268913787,
                          "title": "Кроссовки для бега",
                          "vendorCode": "12345456",
                          "brandName": "Demix",
                          "subjectId": 105,
                          "subjectName": "Кроссовки",
                          "productRating": 4.5,
                          "feedbackRating": 4,
                          "stocks": {"wb": 0, "mp": 0, "balanceSum": 7}
                        },
                        "statistic": {
                          "selected": {
                            "openCount": 45,
                            "cartCount": 34,
                            "orderCount": 19,
                            "orderSum": 1262,
                            "addToWishlist": 455,
                            "cancelCount": 0,
                            "conversions": {
                              "addToCartPercent": 19,
                              "cartToOrderPercent": 65,
                              "buyoutPercent": 0
                            }
                          },
                          "comparison": {
                            "cartCountDynamic": 30,
                            "orderCountDynamic": -100,
                            "addToWishlistDynamic": 60
                          }
                        }
                      }
                    ],
                    "currency": "RUB"
                  }
                }
                """;

        SalesFunnelResponse response = GSON.fromJson(json, SalesFunnelResponse.class);
        SalesFunnelResponse.SalesFunnelProductItem item = response.getItems().getFirst();

        assertEquals(268913787L, item.getProduct().getNmId());
        assertEquals("Кроссовки для бега", item.getProduct().getDisplayName());
        assertEquals("12345456", item.getProduct().getVendorCode());
        assertEquals(4.5, item.getProduct().getProductRating());
        assertEquals(4.0, item.getProduct().getFeedbackRating());
        assertEquals(45, item.getSelected().getOpenCount());
        assertEquals(34, item.getSelected().getCartCount());
        assertEquals(19, item.getSelected().getOrderCount());
        assertEquals(1262.0, item.getSelected().getOrderSum());
        assertEquals(455, item.getSelected().getAddToWishlist());
        assertEquals(19.0, item.getSelected().getAddToCartPercent());
        assertEquals(65.0, item.getSelected().getCartToOrderPercent());
        assertEquals(7, item.getSelected().getStocks().getBalanceSum());
        assertEquals(-100.0, item.getComparison().getOrderCountDynamic());
    }
}
