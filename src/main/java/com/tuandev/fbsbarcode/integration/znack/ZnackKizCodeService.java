package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;

import java.util.ArrayList;
import java.util.List;

public class ZnackKizCodeService {
    private final ZnackApiClient api;private final ZnackAuthService auth;private final ZnackRepository repository;
    public ZnackKizCodeService(ZnackApiClient api,ZnackAuthService auth,ZnackRepository repository){this.api=api;this.auth=auth;this.repository=repository;}
    public int download(Settings s,long id)throws Exception{
        ZnackSafety.requireSigned(s,true);
        KizOrder order=repository.findOrder(id).orElseThrow();JsonElement response=api.codes(s.resolvedSuzBaseUrl(),auth.suzToken(s),s.omsId(),order.externalOrderId(),order.quantity(),order.gtin());
        JsonObject object=response.isJsonObject()?response.getAsJsonObject():new JsonObject();JsonArray array=object.has("codes")?object.getAsJsonArray("codes"):response.getAsJsonArray();
        List<String> codes=new ArrayList<>();if(array!=null)for(JsonElement e:array)codes.add(e.isJsonPrimitive()?e.getAsString():e.getAsJsonObject().get("cis").getAsString());
        String block=object.has("blockId")?object.get("blockId").getAsString():null;int inserted=repository.insertCodes(id,order.gtin(),new DownloadedCodes(codes,block));
        if(!codes.isEmpty())repository.updateOrder(id,null,"READY",OrderStatus.CODES_DOWNLOADED,null);repository.log("DOWNLOAD_CODES",String.valueOf(id),"INFO","Downloaded "+inserted+" new codes",200);return inserted;
    }
}
