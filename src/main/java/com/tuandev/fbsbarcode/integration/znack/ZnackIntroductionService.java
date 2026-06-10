package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class ZnackIntroductionService {
    private final ZnackApiClient api;private final ZnackAuthService auth;private final ZnackSignatureProvider signer;private final ZnackRepository repository;
    public ZnackIntroductionService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,ZnackRepository repository){this.api=api;this.auth=auth;this.signer=signer;this.repository=repository;}
    public long submit(Settings s,KizOrder order,Product product,List<KizCode> codes)throws Exception{
        ZnackSafety.requireSigned(s,true);
        s.validateGoodsDocumentDates();
        if(product.tnVed()==null||product.tnVed().isBlank())throw new IllegalStateException("TN VED is required before introduction.");
        String participant=required(auth.resolvedParticipantInn(s),"Participant INN is required for introduction.");
        String producer=valueOr(s.producerInn(),participant),owner=valueOr(s.ownerInn(),participant);
        GoodsDocument goodsDocument=product.resolvedGoodsDocument(s);
        if(!goodsDocument.complete())throw new IllegalStateException("Missing "+goodsDocument.missingFields()+".");
        Settings.validateGoodsDocumentDate(goodsDocument.date(),"Document issue date");
        JsonObject payload=new JsonObject();payload.addProperty("participant_inn",participant);payload.addProperty("producer_inn",producer);payload.addProperty("owner_inn",owner);payload.addProperty("production_type","OWN_PRODUCTION");
        if(product.productionDate()!=null&&!product.productionDate().isBlank())payload.addProperty("production_date",product.productionDate());
        JsonArray items=new JsonArray();for(KizCode code:codes){JsonObject item=new JsonObject();item.addProperty("uit_code",ZnackCisNormalizer.forTrueApi(code.rawCode()));item.addProperty("tnved_code",product.tnVed());
            if(goodsDocument.complete()){JsonObject certificate=new JsonObject();certificate.addProperty("certificate_type",goodsDocument.type().trim());certificate.addProperty("certificate_number",goodsDocument.number().trim());certificate.addProperty("certificate_date",goodsDocument.date().trim());
                JsonArray certificates=new JsonArray();certificates.add(certificate);item.add("certificate_document_data",certificates);}items.add(item);}
        payload.add("products",items);byte[] documentBytes=payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sig=signer.sign(documentBytes, ZnackSignatureContext.TRUE_API_DOCUMENT).cms();String token=auth.trueApiToken(s);
        JsonObject request=new JsonObject();request.addProperty("document_format","MANUAL");request.addProperty("type","LP_INTRODUCE_GOODS");request.addProperty("product_document",Base64.getEncoder().encodeToString(documentBytes));request.addProperty("signature",Base64.getEncoder().encodeToString(sig));
        long documentId=repository.createDocument(order.id(),payload.toString());
        try{String external=required(api.createDocument(s.resolvedTrueApiBaseUrl(),token,request),"Znack document response did not contain a document ID.");
            repository.updateDocument(documentId,external,"SUBMITTED",null);repository.markCodes(order.id(),KizLegalStatus.INTRO_SENT,null,documentId);repository.updateOrder(order.id(),null,null,OrderStatus.INTRO_SENT,null);return documentId;
        }catch(Exception e){repository.updateDocument(documentId,null,e instanceof ZnackApiClient.ZnackApiException?"REJECTED":"FAILED",e.getMessage());throw e;}
    }
    public boolean confirm(Settings s,KizOrder order,List<KizCode> codes)throws Exception{
        ZnackSafety.requireSigned(s,true);
        Document document=repository.findLatestDocument(order.id()).orElseThrow();
        JsonElement docs=api.document(s.resolvedTrueApiBaseUrl(),auth.trueApiToken(s),document.externalDocumentId());if(!documentCheckedOk(docs,document.externalDocumentId()))return false;
        JsonArray values=new JsonArray();codes.forEach(c->values.add(ZnackCisNormalizer.forTrueApi(c.rawCode())));
        JsonElement info=api.cisesInfo(s.resolvedTrueApiBaseUrl(),auth.trueApiToken(s),values);if(!allIntroduced(info,codes.size()))return false;
        repository.updateDocument(document.id(),null,"CHECKED_OK",null);
        repository.markCodes(order.id(),KizLegalStatus.IN_CIRCULATION,null,null);repository.updateOrder(order.id(),null,null,OrderStatus.INTRODUCED,null);return true;
    }
    private boolean documentCheckedOk(JsonElement e,String externalId){
        if(e==null||externalId==null||externalId.isBlank())return false;
        if(e.isJsonObject()){JsonObject o=e.getAsJsonObject();String id=text(o,"document_id","documentId","id");String status=text(o,"status","documentStatus");
            if((id.isBlank()||externalId.equals(id))&&"CHECKED_OK".equalsIgnoreCase(status))return true;for(var x:o.entrySet())if(documentCheckedOk(x.getValue(),externalId))return true;}
        else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())if(documentCheckedOk(x,externalId))return true;return false;
    }
    private boolean allIntroduced(JsonElement e,int expected){int[] count={0};walk(e,count);return count[0]>=expected;}
    private void walk(JsonElement e,int[] count){if(e==null)return;if(e.isJsonObject()){JsonObject o=e.getAsJsonObject();if(o.has("status")&&!o.get("status").isJsonNull()&&"INTRODUCED".equalsIgnoreCase(o.get("status").getAsString())){count[0]++;return;}for(var x:o.entrySet())walk(x.getValue(),count);}else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())walk(x,count);}
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private String valueOr(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}
    private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalStateException(message);return value.trim();}
}
