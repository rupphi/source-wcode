package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ZnackProductService {
    private static final int PAGE_SIZE = 10_000;
    private final ZnackApiClient api; private final ZnackAuthService auth; private final ZnackRepository repository;
    public ZnackProductService(ZnackApiClient api,ZnackAuthService auth,ZnackRepository repository){this.api=api;this.auth=auth;this.repository=repository;}
    public List<Product> sync(ZnackModels.Settings settings)throws Exception{
        ZnackSafety.requireSigned(settings,false);
        String token=auth.trueApiToken(settings);Map<String,Product> byGtin=new LinkedHashMap<>();int page=0,fetched=0;Integer total=null;
        do{
            JsonElement response=page==0?api.products(settings.resolvedTrueApiBaseUrl(),token):
                    api.products(settings.resolvedTrueApiBaseUrl(),token,page,PAGE_SIZE);
            JsonArray array=response.isJsonArray()?response.getAsJsonArray():response.getAsJsonObject().getAsJsonArray("results");
            if(response.isJsonObject()&&response.getAsJsonObject().has("total")&&!response.getAsJsonObject().get("total").isJsonNull())total=response.getAsJsonObject().get("total").getAsInt();
            if(array!=null)for(JsonElement e:array){JsonObject o=e.getAsJsonObject();String gtin=text(o,"gtin","productGtin");if(!gtin.isBlank()){String normalized=GtinNormalizer.normalize(gtin);byGtin.put(normalized,new Product(
                    normalized,text(o,"productName","name"),text(o,"tnVed","tnved","tnvedCode","tnved_code"),
                    text(o,"certificateType","certificate_type"),text(o,"certificateNumber","certificate_number"),
                    text(o,"certificateDate","certificate_date"),text(o,"productionDate","production_date")));}}
            int received=array==null?0:array.size();fetched+=received;page++;
            if(total==null&&received<PAGE_SIZE)break;
            if(received==0)break;
        }while(total==null||fetched<total);
        List<Product> products=List.copyOf(byGtin.values());
        repository.upsertProducts(products);repository.log("GTIN_SYNC",null,"INFO","Synced "+products.size()+" GTINs",200);return products;
    }
    private String text(JsonObject o,String...keys){for(String k:keys)if(o.has(k)&&!o.get(k).isJsonNull())return o.get(k).getAsString();return "";}
}
