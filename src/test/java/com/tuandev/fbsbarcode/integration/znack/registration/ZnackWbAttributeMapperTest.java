package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZnackWbAttributeMapperTest {
    @Test
    void mapsTheWbCardAndSizeSchemaWithoutManualInput() {
        Sku sku = new Sku(1110091485L, 98765L, 2789, "01-besang", "Брюки",
                "Lyxury", "Брюки палаццо широкие", "Черный", "L", List.of("2039000000012"),
                "https://basket-22.wbbasket.ru/vol3891/part38915/389151234/images/c516x688/1.webp",
                true, "", null, "", Status.NOT_CREATED, "", false);
        List<WbCharacteristic> characteristics = List.of(
                new WbCharacteristic(1, "Состав", List.of("полиэстер 20%, лайкра 5%, хлопок 75%")),
                new WbCharacteristic(2, "Пол", List.of("Женский")),
                new WbCharacteristic(3, "ТН ВЭД", List.of("6204510000"))
        );
        List<Attribute> required = List.of(
                attribute(2478, "Полное наименование товара"),
                attribute(2504, "Товарный знак"),
                attribute(13933, "Код ТНВЭД"),
                attribute(3959, "Группа ТНВЭД"),
                attribute(13914, "Модель / артикул производителя"),
                attribute(35, "Размер одежды / изделия"),
                preset(36, "Цвет", "ЧЕРНЫЙ", "БЕЛЫЙ"),
                attribute(2483, "Состав"),
                preset(14013, "Целевой пол", "МУЖСКОЙ", "ЖЕНСКИЙ"),
                preset(12, "Вид товара", "ЮБКИ", "БРЮКИ"),
                preset(13836, "Номер технического регламента", "ТР ТС 017/2011 О безопасности продукции легкой промышленности"),
                attribute(ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID, "Декларация")
        );

        var result = new ZnackWbAttributeMapper().map(sku, characteristics, required,
                "6204510000", "6204", new ZnackModels.GoodsDocument(
                        "CONFORMITY_DECLARATION", "ЕАЭС N RU Д-RU.РА07.В.89061/26", "2026-09-02"));

        assertTrue(result.complete(), result.missingFields().toString());
        assertEquals("01-besang", result.attributes().get(13914L));
        assertEquals("L", result.attributes().get(35L));
        assertEquals("ЧЕРНЫЙ", result.attributes().get(36L));
        assertEquals("ЖЕНСКИЙ", result.attributes().get(14013L));
        assertEquals("БРЮКИ", result.attributes().get(12L));
        assertEquals("6204510000", result.attributes().get(13933L));
        assertFalse(result.attributes().containsKey(3959L));
    }

    @Test
    void reportsOnlyFieldsThatCannotBeDerivedFromWb() {
        Sku sku = new Sku(1, 2, 3, "ART-1", "Брюки", "Brand", "Брюки",
                "", "44", List.of(), "", true, "", null, "", Status.NOT_CREATED, "", false);
        var result = new ZnackWbAttributeMapper().map(sku, List.of(),
                List.of(attribute(90001, "Изготовитель")), "6204510000", "6204", null);

        assertFalse(result.complete());
        assertTrue(result.missingFields().stream().anyMatch(value -> value.contains("Изготовитель")));
        assertTrue(result.missingFields().stream().anyMatch(value -> value.contains("Giấy tờ")));
    }

    private static Attribute attribute(long id, String name) {
        return new Attribute(id, name, "text", false, false, true, false, List.of());
    }

    private static Attribute preset(long id, String name, String... values) {
        return new Attribute(id, name, "text", true, false, true, false, List.of(values));
    }
}
