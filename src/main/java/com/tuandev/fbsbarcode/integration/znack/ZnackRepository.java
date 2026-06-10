package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class ZnackRepository {
    private final ShopContext shop;

    public ZnackRepository(ShopContext shop) {
        this.shop = Objects.requireNonNull(shop);
    }

    public ShopContext shop() { return shop; }

    public Settings getSettings() {
        try (Connection c=Database.getConnection(); PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_settings WHERE shop_id=?")) {
            ps.setInt(1,shop.shopId());
            try(ResultSet r=ps.executeQuery()){
                if(!r.next())return Settings.empty();
                return new Settings(r.getString("true_api_base_url"),r.getString("suz_base_url"),r.getString("oms_id"),r.getString("oms_connection"),
                        r.getString("participant_inn"),r.getString("producer_inn"),r.getString("owner_inn"),r.getString("signer_executable"),
                        r.getString("signer_certificate"),r.getString("signer_arguments_json"),r.getString("document_number"),r.getString("document_date"),
                        r.getString("pdf_folder"),r.getInt("auto_introduction")!=0,r.getString("certificate_list_executable"),
                        r.getString("certificate_list_arguments_json"),r.getString("certificate_metadata_json"),instant(r.getString("signer_tested_at")),
                        r.getString("certmgr_path"),r.getString("cryptcp_path"),r.getString("csptest_path"),r.getInt("cryptopro_timeout_seconds"),
                        r.getString("document_expiry_date"));
            }
        }catch(SQLException e){throw new RuntimeException(e);}
    }

    public void saveSettings(Settings s) {
        String sql="""
                INSERT INTO znack_settings(shop_id,true_api_base_url,suz_base_url,oms_id,oms_connection,participant_inn,
                producer_inn,owner_inn,signer_executable,signer_certificate,signer_arguments_json,document_number,
                document_date,pdf_folder,auto_introduction,certificate_list_executable,certificate_list_arguments_json,
                certificate_metadata_json,signer_tested_at,certmgr_path,cryptcp_path,csptest_path,cryptopro_timeout_seconds,
                document_expiry_date,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id) DO UPDATE SET true_api_base_url=excluded.true_api_base_url,suz_base_url=excluded.suz_base_url,
                oms_id=excluded.oms_id,oms_connection=excluded.oms_connection,participant_inn=excluded.participant_inn,
                producer_inn=excluded.producer_inn,owner_inn=excluded.owner_inn,signer_executable=excluded.signer_executable,
                signer_certificate=excluded.signer_certificate,signer_arguments_json=excluded.signer_arguments_json,
                document_number=excluded.document_number,document_date=excluded.document_date,pdf_folder=excluded.pdf_folder,
                auto_introduction=excluded.auto_introduction,certificate_list_executable=excluded.certificate_list_executable,
                certificate_list_arguments_json=excluded.certificate_list_arguments_json,certificate_metadata_json=excluded.certificate_metadata_json,
                signer_tested_at=excluded.signer_tested_at,certmgr_path=excluded.certmgr_path,cryptcp_path=excluded.cryptcp_path,
                csptest_path=excluded.csptest_path,cryptopro_timeout_seconds=excluded.cryptopro_timeout_seconds,
                document_expiry_date=excluded.document_expiry_date,updated_at=excluded.updated_at
                """;
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){
            int i=1;ps.setInt(i++,shop.shopId());ps.setString(i++,s.trueApiBaseUrl());ps.setString(i++,s.suzBaseUrl());ps.setString(i++,s.omsId());
            ps.setString(i++,s.omsConnection());ps.setString(i++,s.participantInn());ps.setString(i++,s.producerInn());ps.setString(i++,s.ownerInn());
            ps.setString(i++,s.signerExecutable());ps.setString(i++,s.signerCertificate());ps.setString(i++,s.signerArgumentsJson());
            ps.setString(i++,s.documentNumber());ps.setString(i++,s.documentDate());ps.setString(i++,s.pdfFolder());ps.setInt(i++,s.autoIntroduction()?1:0);
            ps.setString(i++,s.certificateListExecutable());ps.setString(i++,s.certificateListArgumentsJson());ps.setString(i++,s.certificateMetadataJson());
            ps.setString(i++,s.signerTestedAt()==null?null:s.signerTestedAt().toString());ps.setString(i++,s.certmgrPath());ps.setString(i++,s.cryptcpPath());
            ps.setString(i++,s.csptestPath());ps.setInt(i++,s.resolvedCryptoProTimeoutSeconds());ps.setString(i++,s.documentExpiryDate());
            ps.setString(i,Instant.now().toString());ps.executeUpdate();
        }catch(SQLException e){throw new RuntimeException(e);}
    }

    public void upsertProducts(List<Product> products){
        String sql="""
                INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,certificate_type,certificate_number,
                                           certificate_date,production_date,synced_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,gtin) DO UPDATE SET
                  product_name=COALESCE(NULLIF(excluded.product_name,''),znack_products.product_name),
                  tn_ved=COALESCE(NULLIF(excluded.tn_ved,''),znack_products.tn_ved),
                  certificate_type=COALESCE(NULLIF(excluded.certificate_type,''),znack_products.certificate_type),
                  certificate_number=COALESCE(NULLIF(excluded.certificate_number,''),znack_products.certificate_number),
                  certificate_date=COALESCE(NULLIF(excluded.certificate_date,''),znack_products.certificate_date),
                  production_date=COALESCE(NULLIF(excluded.production_date,''),znack_products.production_date),
                  synced_at=excluded.synced_at
                """;
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){c.setAutoCommit(false);for(Product p:products){int i=1;ps.setInt(i++,shop.shopId());ps.setString(i++,GtinNormalizer.normalize(p.gtin()));ps.setString(i++,p.productName());ps.setString(i++,p.tnVed());ps.setString(i++,p.certificateType());ps.setString(i++,p.certificateNumber());ps.setString(i++,p.certificateDate());ps.setString(i++,p.productionDate());ps.setString(i,Instant.now().toString());ps.addBatch();}ps.executeBatch();c.commit();}catch(SQLException e){throw new RuntimeException(e);}
    }
    public List<Product> findProducts(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_products WHERE shop_id=? ORDER BY gtin")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<Product> o=new ArrayList<>();while(r.next())o.add(new Product(r.getString("gtin"),r.getString("product_name"),r.getString("tn_ved"),r.getString("certificate_type"),r.getString("certificate_number"),r.getString("certificate_date"),r.getString("production_date")));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public Optional<Product> findProduct(String gtin){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_products WHERE shop_id=? AND gtin=?")){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(new Product(r.getString("gtin"),r.getString("product_name"),r.getString("tn_ved"),r.getString("certificate_type"),r.getString("certificate_number"),r.getString("certificate_date"),r.getString("production_date"))):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updateProductMetadata(Product p){execute("UPDATE znack_products SET tn_ved=?,certificate_type=?,certificate_number=?,certificate_date=?,production_date=? WHERE shop_id=? AND gtin=?",ps->{ps.setString(1,p.tnVed());ps.setString(2,p.certificateType());ps.setString(3,p.certificateNumber());ps.setString(4,p.certificateDate());ps.setString(5,p.productionDate());ps.setInt(6,shop.shopId());ps.setString(7,GtinNormalizer.normalize(p.gtin()));});}

    public long createDraft(String gtin,int quantity){String now=Instant.now().toString();try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));ps.setInt(3,quantity);ps.setString(4,OrderStatus.DRAFT.name());ps.setString(5,now);ps.setString(6,now);ps.executeUpdate();try(ResultSet r=ps.getGeneratedKeys()){r.next();return r.getLong(1);}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updateOrder(long id,String external,String remote,OrderStatus status,String error){execute("UPDATE kiz_orders SET external_order_id=COALESCE(?,external_order_id),remote_status=?,local_status=?,error_message=?,updated_at=? WHERE shop_id=? AND id=?",ps->{ps.setString(1,external);ps.setString(2,remote);ps.setString(3,status.name());ps.setString(4,ZnackSanitizer.message(error));ps.setString(5,Instant.now().toString());ps.setInt(6,shop.shopId());ps.setLong(7,id);});}
    public Optional<KizOrder> findOrder(long id){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM kiz_orders WHERE shop_id=? AND id=?")){ps.setInt(1,shop.shopId());ps.setLong(2,id);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(order(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<KizOrder> findOrders(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM kiz_orders WHERE shop_id=? ORDER BY id DESC")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<KizOrder> o=new ArrayList<>();while(r.next())o.add(order(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}

    public int insertCodes(long orderId,String gtin,DownloadedCodes d){String sql="INSERT OR IGNORE INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,block_id,status,legal_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){c.setAutoCommit(false);int n=0;String now=Instant.now().toString();for(String raw:d.codes()){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);ps.setString(3,raw);ps.setString(4,ZnackSanitizer.displayCode(raw));ps.setString(5,GtinNormalizer.normalize(gtin));ps.setString(6,d.blockId());ps.setString(7,KizInventoryStatus.AVAILABLE.name());ps.setString(8,KizLegalStatus.RECEIVED.name());ps.setString(9,now);ps.setString(10,now);n+=ps.executeUpdate();}c.commit();return n;}catch(SQLException e){throw new RuntimeException(e);}}
    public List<KizCode> findCodes(long orderId){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM kiz_codes WHERE shop_id=? AND order_id=? ORDER BY id")){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);try(ResultSet r=ps.executeQuery()){List<KizCode> o=new ArrayList<>();while(r.next())o.add(code(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public void markCodes(long orderId,KizLegalStatus status,String pdfPath,Long documentId){execute("UPDATE kiz_codes SET legal_status=?,document_id=COALESCE(?,document_id),updated_at=? WHERE shop_id=? AND order_id=?",ps->{ps.setString(1,status.name());if(documentId==null)ps.setNull(2,Types.BIGINT);else ps.setLong(2,documentId);ps.setString(3,Instant.now().toString());ps.setInt(4,shop.shopId());ps.setLong(5,orderId);});}

    public long createPipeline(String gtin,int quantity){String now=Instant.now().toString();try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,stage,created_at,updated_at) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));ps.setInt(3,quantity);ps.setString(4,PurchaseStage.VALIDATING.name());ps.setString(5,now);ps.setString(6,now);ps.executeUpdate();try(ResultSet r=ps.getGeneratedKeys()){r.next();return r.getLong(1);}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updatePipeline(long id,Long orderId,PurchaseStage stage,String error){execute("UPDATE znack_purchase_pipelines SET order_id=COALESCE(?,order_id),stage=?,error_message=?,updated_at=? WHERE shop_id=? AND id=?",ps->{if(orderId==null)ps.setNull(1,Types.BIGINT);else ps.setLong(1,orderId);ps.setString(2,stage.name());ps.setString(3,ZnackSanitizer.message(error));ps.setString(4,Instant.now().toString());ps.setInt(5,shop.shopId());ps.setLong(6,id);});}
    public Optional<ZnackPurchasePipelineState> findActivePipeline(String gtin){String sql="SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=? AND stage NOT IN ('COMPLETED','INTRODUCED','FAILED','INTRODUCTION_SKIPPED_MISSING_DOCUMENTS','INTRODUCTION_SKIPPED_MISSING_METADATA') ORDER BY id DESC LIMIT 1";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());ps.setString(2,GtinNormalizer.normalize(gtin));try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(pipeline(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findActivePipelines(){String sql="SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND stage NOT IN ('COMPLETED','INTRODUCED','FAILED','INTRODUCTION_SKIPPED_MISSING_DOCUMENTS','INTRODUCTION_SKIPPED_MISSING_METADATA') ORDER BY id";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public List<ZnackPurchasePipelineState> findSkippedIntroductionPipelines(){String sql="SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND stage IN ('INTRODUCTION_SKIPPED_MISSING_DOCUMENTS','INTRODUCTION_SKIPPED_MISSING_METADATA') ORDER BY id";try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<ZnackPurchasePipelineState> o=new ArrayList<>();while(r.next())o.add(pipeline(r));return o;}}catch(SQLException e){throw new RuntimeException(e);}}
    public Optional<ZnackPurchasePipelineState> findPipeline(long id){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_purchase_pipelines WHERE shop_id=? AND id=?")){ps.setInt(1,shop.shopId());ps.setLong(2,id);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(pipeline(r)):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}

    public long createDocument(long orderId,String payload){String now=Instant.now().toString();try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO znack_documents(shop_id,order_id,document_type,payload_json,status,created_at,updated_at) VALUES(?,?,'LP_INTRODUCE_GOODS',?,'DRAFT',?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);ps.setString(3,payload);ps.setString(4,now);ps.setString(5,now);ps.executeUpdate();try(ResultSet r=ps.getGeneratedKeys()){r.next();return r.getLong(1);}}catch(SQLException e){throw new RuntimeException(e);}}
    public void updateDocument(long id,String external,String status,String error){execute("UPDATE znack_documents SET external_document_id=COALESCE(?,external_document_id),status=?,error_message=?,updated_at=? WHERE shop_id=? AND id=?",ps->{ps.setString(1,external);ps.setString(2,status);ps.setString(3,ZnackSanitizer.message(error));ps.setString(4,Instant.now().toString());ps.setInt(5,shop.shopId());ps.setLong(6,id);});}
    public Optional<Document> findLatestDocument(long orderId){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT id,order_id,payload_json,external_document_id,status FROM znack_documents WHERE shop_id=? AND order_id=? ORDER BY id DESC LIMIT 1")){ps.setInt(1,shop.shopId());ps.setLong(2,orderId);try(ResultSet r=ps.executeQuery()){return r.next()?Optional.of(new Document(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5))):Optional.empty();}}catch(SQLException e){throw new RuntimeException(e);}}

    public void log(String action,String entity,String severity,String message,Integer httpStatus){execute("INSERT INTO znack_operation_logs(shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at) VALUES(?,?,?,?,?,?,?,?)",ps->{ps.setInt(1,shop.shopId());ps.setString(2,shop.shopName());ps.setString(3,action);ps.setString(4,entity);ps.setString(5,severity);ps.setString(6,ZnackSanitizer.message(message));if(httpStatus==null)ps.setNull(7,Types.INTEGER);else ps.setInt(7,httpStatus);ps.setString(8,Instant.now().toString());});}
    public List<OperationLog> findLogs(){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM znack_operation_logs WHERE shop_id=? ORDER BY id DESC")){ps.setInt(1,shop.shopId());try(ResultSet r=ps.executeQuery()){List<OperationLog> o=new ArrayList<>();while(r.next()){int h=r.getInt("http_status");o.add(new OperationLog(r.getLong("id"),r.getInt("shop_id"),r.getString("shop_name"),r.getString("action"),r.getString("entity_reference"),r.getString("severity"),r.getString("message"),r.wasNull()?null:h,Instant.parse(r.getString("created_at"))));}return o;}}catch(SQLException e){throw new RuntimeException(e);}}

    private void execute(String sql,SqlBinder binder){try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){binder.bind(ps);ps.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);}}
    private KizOrder order(ResultSet r)throws SQLException{return new KizOrder(r.getLong("id"),r.getString("external_order_id"),r.getString("gtin"),r.getInt("quantity"),r.getString("remote_status"),OrderStatus.valueOf(r.getString("local_status")),r.getString("error_message"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    private KizCode code(ResultSet r)throws SQLException{long d=r.getLong("document_id");Long documentId=r.wasNull()?null:d;String legal=r.getString("legal_status");return new KizCode(r.getLong("id"),r.getLong("order_id"),r.getString("raw_code"),r.getString("display_code"),r.getString("gtin"),r.getString("block_id"),r.getString("pdf_path"),documentId,KizInventoryStatus.valueOf(r.getString("status")),legal==null||legal.isBlank()?null:KizLegalStatus.valueOf(legal));}
    private ZnackPurchasePipelineState pipeline(ResultSet r)throws SQLException{long orderId=r.getLong("order_id");boolean orderNull=r.wasNull();return new ZnackPurchasePipelineState(r.getLong("id"),r.getInt("shop_id"),r.getString("gtin"),r.getInt("quantity"),orderNull?null:orderId,PurchaseStage.valueOf(r.getString("stage")),r.getString("error_message"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    private static Instant instant(String value){return value==null||value.isBlank()?null:Instant.parse(value);}
    @FunctionalInterface private interface SqlBinder{void bind(PreparedStatement ps)throws SQLException;}
}
