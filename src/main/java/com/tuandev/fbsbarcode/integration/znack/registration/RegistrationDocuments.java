package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.GoodsDocument;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/** Registration-only settings; legacy procurement defaults are not overwritten. */
public final class RegistrationDocuments {
    private RegistrationDocuments() {}

    public static List<GoodsDocument> parse(String declaration, String declarationDate,
                                             String certificate, String certificateDate) {
        List<GoodsDocument> result = new ArrayList<>();
        add(result, "CONFORMITY_DECLARATION", declaration, declarationDate);
        add(result, "CONFORMITY_CERTIFICATE", certificate, certificateDate);
        if (result.isEmpty()) throw new IllegalArgumentException("znack.registration.config_required");
        return List.copyOf(result);
    }

    private static void add(List<GoodsDocument> result, String type, String number, String date) {
        number = number == null ? "" : number.strip();
        date = date == null ? "" : date.strip();
        if (number.isEmpty() && date.isEmpty()) return;
        if (number.isEmpty() || date.isEmpty()) throw new IllegalArgumentException("znack.registration.config_required");
        try {
            LocalDate parsed = date.contains(".")
                    ? LocalDate.parse(date, DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT))
                    : LocalDate.parse(date);
            result.add(new GoodsDocument(type, number, parsed.toString()));
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("znack.registration.invalid_document_date", error);
        }
    }

    public static List<GoodsDocument> load(int shopId, Settings legacy) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT declaration_number,declaration_date,certificate_number,certificate_date FROM znack_registration_documents WHERE shop_id=?")) {
            s.setInt(1, shopId);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return parse(r.getString(1), r.getString(2), r.getString(3), r.getString(4));
            }
            if (!legacy.hasDefaultGoodsDocument()) return List.of();
            GoodsDocument doc = legacy.defaultGoodsDocument();
            return doc.type().contains("CERTIFICATE") ? parse("", "", doc.number(), doc.date())
                    : parse(doc.number(), doc.date(), "", "");
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    public static void save(int shopId, String declaration, String declarationDate,
                            String certificate, String certificateDate) {
        List<GoodsDocument> documents = parse(declaration, declarationDate, certificate, certificateDate);
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO znack_registration_documents VALUES(?,?,?,?,?)
                ON CONFLICT(shop_id) DO UPDATE SET declaration_number=excluded.declaration_number,
                declaration_date=excluded.declaration_date,certificate_number=excluded.certificate_number,
                certificate_date=excluded.certificate_date
                """)) {
            s.setInt(1, shopId);
            for (int i = 2; i <= 5; i++) s.setString(i, "");
            for (GoodsDocument doc : documents) {
                int index = doc.type().contains("CERTIFICATE") ? 4 : 2;
                s.setString(index, doc.number()); s.setString(index + 1, doc.date());
            }
            s.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
}
