package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;

public class ZnackKizOrderService {
    private final ZnackApiClient api; private final ZnackAuthService auth; private final ZnackSignatureProvider signer; private final ZnackRepository repository;
    public ZnackKizOrderService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,ZnackRepository repository){this.api=api;this.auth=auth;this.signer=signer;this.repository=repository;}
    public KizOrder buy(Settings s,String gtin,int quantity)throws Exception{
        ZnackSafety.requireSigned(s,true);
        if(quantity<=0)throw new IllegalArgumentException("Quantity must be positive.");gtin=GtinNormalizer.requireProductionOrderable(gtin);long id=repository.createDraft(gtin,quantity);
        try{
            JsonObject product=new JsonObject();product.addProperty("gtin",gtin);product.addProperty("quantity",quantity);
            product.addProperty("serialNumberType","OPERATOR");product.addProperty("templateId",10);product.addProperty("cisType","UNIT");
            JsonObject attributes=new JsonObject();attributes.addProperty("releaseMethodType","PRODUCTION");
            JsonObject order=new JsonObject();order.addProperty("productGroup","lp");order.add("attributes",attributes);
            JsonArray products=new JsonArray();products.add(product);order.add("products",products);
            byte[] body=order.toString().getBytes(StandardCharsets.UTF_8);
            String signature=signer.sign(body, ZnackSignatureContext.SUZ_POST_BODY).base64();
            String token=auth.suzToken(s);
            try {
                JsonObject response=api.createOrder(s.resolvedSuzBaseUrl(),token,s.omsId(),body,signature);String external=required(text(response,"orderId","id"),"Znack order response did not contain an order ID.");
                repository.updateOrder(id,external,"CREATED",OrderStatus.SUBMITTED,null);
                try { repository.log("BUY_KIZ",String.valueOf(id),"INFO","Order submitted",200); }
                catch (RuntimeException ignored) {
                    // An audit-log failure must not turn an accepted order into an ambiguous purchase.
                }
                return repository.findOrder(id).orElseThrow();
            } catch (ZnackApiClient.ZnackApiException e) {
                if (e.statusCode() >= 400 && e.statusCode() < 500) throw e;
                throw new ZnackOrderCreationAmbiguousException("Order creation result is ambiguous after a Znack API server error.",e);
            } catch (Exception e) {
                throw new ZnackOrderCreationAmbiguousException("Order creation result is ambiguous; automatic retry is blocked.",e);
            }
        }catch(Exception e){try{repository.updateOrder(id,null,null,OrderStatus.FAILED,e.getMessage());}catch(RuntimeException auditError){e.addSuppressed(auditError);}throw e;}
    }
    public KizOrder refresh(Settings s,long id)throws Exception{
        ZnackSafety.requireSigned(s,true);
        KizOrder order=repository.findOrder(id).orElseThrow();JsonArray response=api.orderStatus(s.resolvedSuzBaseUrl(),auth.suzToken(s),s.omsId(),required(order.externalOrderId(),"Znack order has no external order ID."));
        String remote="PENDING",reason=null;int available=0;boolean rejected=false;
        for(JsonElement e:response){JsonObject o=e.getAsJsonObject();remote=text(o,"bufferStatus","status");available+=integer(o,"availableCodes");reason=text(o,"rejectionReason");rejected|="REJECTED".equalsIgnoreCase(remote);}
        BufferStatus status=new BufferStatus(remote,available,rejected,reason);repository.updateOrder(id,null,remote,status.localStatus(),reason);return repository.findOrder(id).orElseThrow();
    }
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private int integer(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0;}
    private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalStateException(message);return value;}
}
