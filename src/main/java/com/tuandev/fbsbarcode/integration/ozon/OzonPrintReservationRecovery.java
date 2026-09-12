package com.tuandev.fbsbarcode.integration.ozon;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;

/** Repairs the pre-1.1.28 print-failure release without ever moving a mark to a different posting. */
final class OzonPrintReservationRecovery {
    private OzonPrintReservationRecovery() { }

    static void recover(Connection connection) throws SQLException {
        try (var transaction = connection.createStatement()) {
            transaction.execute("SAVEPOINT ozon_print_recovery");
            try {
                var ids = new ArrayList<Long>();
                try (var query = connection.prepareStatement("""
                        SELECT j.id FROM ozon_exemplar_jobs j JOIN shops s ON s.id=j.shop_id
                        WHERE s.marketplace='OZON' AND j.stage='REJECTED' AND j.safe_error_code='print_failed'
                          AND j.mutation_attempted_at IS NULL
                          AND EXISTS (SELECT 1 FROM ozon_exemplars e WHERE e.job_id=j.id)
                          AND NOT EXISTS (
                            SELECT 1 FROM ozon_exemplars e LEFT JOIN kiz_codes k ON k.id=e.kiz_id
                            WHERE e.job_id=j.id AND (
                              k.id IS NULL OR e.shop_id<>j.shop_id OR k.shop_id<>j.shop_id
                              OR e.posting_number<>j.posting_number
                              OR COALESCE(k.legal_status,'')<>'IN_CIRCULATION' OR k.consumed_at IS NOT NULL
                              OR COALESCE(e.check_status,'') IN ('passed','printed')
                              OR NOT (k.status='AVAILABLE' AND k.reservation_token IS NULL
                                OR k.status='RESERVED' AND COALESCE(k.reservation_token,'')='ozon:' || j.id)
                            ))
                        """); var rows = query.executeQuery()) {
                    while (rows.next()) ids.add(rows.getLong(1));
                }
                String now = Instant.now().toString();
                for (long id : ids) {
                    try (var reserve = connection.prepareStatement("""
                            UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,
                                reservation_recoverable=0,updated_at=?
                            WHERE id IN (SELECT kiz_id FROM ozon_exemplars WHERE job_id=?)
                            """); var resume = connection.prepareStatement("""
                            UPDATE ozon_exemplar_jobs SET stage='RESERVED',safe_error_code=NULL,updated_at=? WHERE id=?
                            """)) {
                        reserve.setString(1, "ozon:" + id);
                        reserve.setString(2, now);
                        reserve.setString(3, now);
                        reserve.setLong(4, id);
                        reserve.executeUpdate();
                        resume.setString(1, now);
                        resume.setLong(2, id);
                        resume.executeUpdate();
                    }
                }
                transaction.execute("RELEASE SAVEPOINT ozon_print_recovery");
            } catch (SQLException | RuntimeException error) {
                transaction.execute("ROLLBACK TO SAVEPOINT ozon_print_recovery");
                transaction.execute("RELEASE SAVEPOINT ozon_print_recovery");
                throw error;
            }
        }
    }
}
