package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public class ZnackProductService {
    private static final int PAGE_SIZE = 10_000;
    private static final int CATALOG_BATCH_SIZE = 25;
    private final ZnackApiClient api; private final ZnackAuthService auth; private final ZnackRepository repository;
    public ZnackProductService(ZnackApiClient api,ZnackAuthService auth,ZnackRepository repository){this.api=api;this.auth=auth;this.repository=repository;}
    public List<Product> sync(ZnackModels.Settings settings)throws Exception{
        ZnackSafety.requireSigned(settings,false);
        String token=auth.trueApiToken(settings);Map<String,Product> byGtin=new LinkedHashMap<>();int page=0,fetched=0,technical=0;Integer total=null;
        do{
            JsonElement response=page==0?api.products(settings.resolvedTrueApiBaseUrl(),token):
                    api.products(settings.resolvedTrueApiBaseUrl(),token,page,PAGE_SIZE);
            JsonArray array=response.isJsonArray()?response.getAsJsonArray():response.getAsJsonObject().getAsJsonArray("results");
            if(response.isJsonObject()&&response.getAsJsonObject().has("total")&&!response.getAsJsonObject().get("total").isJsonNull())total=response.getAsJsonObject().get("total").getAsInt();
            if(array!=null)for(JsonElement e:array){JsonObject o=e.getAsJsonObject();String gtin=text(o,"gtin","productGtin");if(!gtin.isBlank()){String normalized=GtinNormalizer.normalize(gtin);if(GtinNormalizer.isTechnicalRange(normalized)){technical++;continue;}byGtin.put(normalized,new Product(
                    normalized,text(o,"productName","name"),tnVed(o),
                    text(o,"certificateType","certificate_type"),text(o,"certificateNumber","certificate_number"),
                    text(o,"certificateDate","certificate_date"),text(o,"productionDate","production_date"),
                    bool(o,"goodMarkFlag","good_mark_flag"),bool(o,"goodTurnFlag","good_turn_flag"),
                    text(o,"goodStatus","good_status","cardStatus"),text(o,"goodDetailedStatus","good_detailed_status"),
                    null));}}
            int received=array==null?0:array.size();fetched+=received;page++;
            if(total==null&&received<PAGE_SIZE)break;
            if(received==0)break;
        }while(total==null||fetched<total);
        enrichFromNationalCatalog(settings, token, byGtin);
        List<Product> publishable=new ArrayList<>();List<String> unpublished=new ArrayList<>();
        for(Product p:byGtin.values()){if(ZnackCardStatus.isErrored(p.cardStatus(),p.cardDetailedStatus()))unpublished.add(p.gtin());else publishable.add(p);}
        repository.upsertProducts(publishable);
        int removed=repository.pruneTechnicalProducts();int unpublishedRemoved=repository.deleteUnpublishedProducts(unpublished);
        repository.log("GTIN_SYNC",null,"INFO","Synced "+publishable.size()+" orderable GTINs; ignored "+technical+
                " technical GTINs; skipped "+unpublished.size()+" non-published cards; removed "+removed+
                " unreferenced technical GTINs and "+unpublishedRemoved+" unreferenced non-published GTINs",200);
        return repository.findProducts();
    }
    private void enrichFromNationalCatalog(ZnackModels.Settings settings,String token,Map<String,Product> byGtin){
        List<String> gtins=List.copyOf(byGtin.keySet());
        for(int start=0;start<gtins.size();start+=CATALOG_BATCH_SIZE){
            List<String> batch=gtins.subList(start,Math.min(start+CATALOG_BATCH_SIZE,gtins.size()));
            try{
                JsonElement response=api.productCards(settings.resolvedTrueApiBaseUrl(),token,String.join(";",batch));
                JsonArray cards=array(response,"result");
                if(cards==null)continue;
                for(JsonElement element:cards){
                    if(!element.isJsonObject())continue;
                    JsonObject card=element.getAsJsonObject();
                    String name=text(card,"good_name","productName","name");
                    String tnVed=tnVed(card);
                    JsonArray identifiers=array(card,"identified_by");
                    if(identifiers==null)continue;
                    for(JsonElement identifier:identifiers){
                        if(!identifier.isJsonObject())continue;
                        JsonObject object=identifier.getAsJsonObject();
                        String type=text(object,"type");
                        String value=text(object,"value","gtin");
                        if(!type.isBlank()&&!"gtin".equalsIgnoreCase(type))continue;
                        try{
                            String gtin=GtinNormalizer.normalize(value);
                            Product current=byGtin.get(gtin);
                            if(current!=null)byGtin.put(gtin,new Product(gtin,first(name,current.productName()),
                                    first(tnVed,current.tnVed()),current.certificateType(),current.certificateNumber(),
                                    current.certificateDate(),current.productionDate(),
                                    first(bool(card,"goodMarkFlag","good_mark_flag"),current.goodMarkFlag()),
                                    first(bool(card,"goodTurnFlag","good_turn_flag"),current.goodTurnFlag()),
                                    first(text(card,"goodStatus","good_status","cardStatus"),current.cardStatus()),
                                    first(text(card,"goodDetailedStatus","good_detailed_status"),current.cardDetailedStatus()),
                                    Instant.now()));
                        }catch(IllegalArgumentException ignored){}
                    }
                }
            }catch(Exception error){
                repository.log("GTIN_CATALOG_ENRICH",null,"WARN",error.getMessage(),null);
            }
        }
    }
    private JsonArray array(JsonElement response,String key){
        if(response==null||response.isJsonNull())return null;
        if(response.isJsonArray())return response.getAsJsonArray();
        JsonObject object=response.getAsJsonObject();
        return object.has(key)&&object.get(key).isJsonArray()?object.getAsJsonArray(key):null;
    }
    private String first(String preferred,String fallback){return preferred==null||preferred.isBlank()||"-".equals(preferred.trim())?fallback:preferred;}
    private Boolean first(Boolean preferred,Boolean fallback){return preferred==null?fallback:preferred;}
    private String tnVed(JsonObject product){
        String direct=text(product,"tnVedCode10","tnvedCode10","tnVed10","tnved10","tnved_code_10","tnved_10",
                "tnVedEaes","productTnved","product_tnved","goodsTnvedCode","tnVed","tnved","tnVedCode",
                "tnvedCode","tnved_code","tnVedEaesGroup");
        String fullCode=attribute(product,13933,"Код ТНВЭД","FEACN code");
        String group=attribute(product,3959,"Группа ТНВЭД","FEACN group");
        return compact(first(fullCode,first(direct,group)));
    }
    private String attribute(JsonObject product,int id,String...names){
        JsonArray attributes=array(product,"good_attrs");
        if(attributes==null)attributes=array(product,"goodAttrs");
        if(attributes==null)return "";
        for(JsonElement element:attributes){
            if(!element.isJsonObject())continue;
            JsonObject attribute=element.getAsJsonObject();
            String attrId=text(attribute,"attr_id","attrId");
            String attrName=text(attribute,"attr_name","attrName");
            boolean matchesId=String.valueOf(id).equals(attrId);
            boolean matchesName=java.util.Arrays.stream(names).anyMatch(name->name.equalsIgnoreCase(attrName));
            if(matchesId||matchesName)return text(attribute,"attr_value","attrValue","value");
        }
        return "";
    }
    private String compact(String value){return value==null?"":value.replaceAll("\\s+","").trim();}
    private Boolean bool(JsonObject o,String...keys){for(String k:keys)if(o.has(k)&&!o.get(k).isJsonNull()){JsonElement value=o.get(k);if(value.isJsonPrimitive()){JsonPrimitive primitive=value.getAsJsonPrimitive();if(primitive.isBoolean())return primitive.getAsBoolean();String text=primitive.getAsString();if("true".equalsIgnoreCase(text)||"1".equals(text))return true;if("false".equalsIgnoreCase(text)||"0".equals(text))return false;}}return null;}
    private String text(JsonObject o,String...keys){for(String k:keys)if(o.has(k)&&!o.get(k).isJsonNull()){JsonElement value=o.get(k);return value.isJsonPrimitive()?value.getAsString():value.toString();}return "";}
}
