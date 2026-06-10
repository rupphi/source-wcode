package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ZnackApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackApiClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    private final OkHttpClient client;
    private final Gson gson = new Gson();

    public ZnackApiClient() {
        this(new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(40)).build());
    }

    ZnackApiClient(OkHttpClient client) {
        this.client = client;
    }

    public JsonObject authKey(String base) throws IOException { return get(authBase(base), "/auth/key", null).getAsJsonObject(); }
    public JsonObject signIn(String base, String connection, JsonObject body) throws IOException {
        return post(authBase(base), "/auth/simpleSignIn" + (connection == null || connection.isBlank() ? "" : "/" + connection), null, body).getAsJsonObject();
    }
    public JsonElement products(String base, String token) throws IOException { return products(base, token, 0, 10_000); }
    public JsonElement products(String base, String token, int page, int limit) throws IOException {
        return get(trueApiBase(base, 4), "/product/gtin?includeSubaccount=false&limit=" + limit + "&page=" + page + "&pg=lp", token);
    }
    public JsonElement productCards(String base, String token, String gtins) throws IOException {
        return get(trueApiBase(base, 3), "/nk/feed-product?gtins=" + url(gtins), token);
    }
    public JsonObject createOrder(String base,String token,String omsId,byte[] body,String signature)throws IOException{
        Request request=new Request.Builder().url(join(base,"/api/v3/order?omsId="+url(omsId))).headers(suzHeaders(token).newBuilder().add("X-Signature",signature).build())
                .post(RequestBody.create(body,JSON)).build();
        return execute(request).getAsJsonObject();
    }
    public JsonArray orderStatus(String base,String token,String omsId,String orderId)throws IOException{return suzGet(base,"/api/v3/order/status?omsId="+url(omsId)+"&orderId="+url(orderId),token).getAsJsonArray();}
    public JsonElement codes(String base,String token,String omsId,String orderId,int quantity,String gtin)throws IOException{
        return suzGet(base,"/api/v3/codes?omsId="+url(omsId)+"&orderId="+url(orderId)+"&quantity="+quantity+"&gtin="+url(gtin),token);
    }
    public String createDocument(String base,String token,JsonObject body)throws IOException{
        String endpoint=join(trueApiBase(base,3),"/lk/documents/create?pg=lp");
        JsonElement response=post(trueApiBase(base,3),"/lk/documents/create?pg=lp",token,body);
        String documentId=documentId(response);
        if(!documentId.isBlank())return documentId;
        String raw=response==null?"null":response.toString();
        LOGGER.error("Znack API returned an unexpected document creation response. url={}, responseBody={}",
                endpoint,ZnackSanitizer.diagnostic(raw));
        throw new IOException("Znack document creation response did not contain a document ID. Response: "
                +ZnackSanitizer.message(raw));
    }
    public JsonElement document(String base,String token,String documentId)throws IOException{
        return get(trueApiBase(base,4),"/doc/"+url(documentId)+"/info?pg=lp",token);
    }
    public JsonElement cisesInfo(String base,String token,JsonElement body)throws IOException{
        Request request=new Request.Builder().url(join(trueApiBase(base,3),"/cises/info?pg=lp")).headers(headers(token))
                .post(RequestBody.create(gson.toJson(body),JSON)).build();
        return execute(request,true);
    }

    private JsonElement get(String base,String path,String token)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(headers(token)).get().build());}
    private JsonElement suzGet(String base,String path,String token)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(suzHeaders(token)).get().build());}
    private JsonElement post(String base,String path,String token,Object body)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(headers(token)).post(RequestBody.create(gson.toJson(body),JSON)).build());}
    private Headers headers(String token){Headers.Builder h=new Headers.Builder().add("Accept","application/json");if(token!=null&&!token.isBlank())h.add("Authorization","Bearer "+token);return h.build();}
    private Headers suzHeaders(String token){Headers.Builder h=new Headers.Builder().add("Accept","application/json");if(token!=null&&!token.isBlank())h.add("clientToken",token);return h.build();}
    private JsonElement execute(Request request)throws IOException{return execute(request,false);}
    private JsonElement execute(Request request,boolean allowNotFoundBody)throws IOException{
        try(Response response=client.newCall(request).execute()){
            String body=response.body()==null?"":response.body().string();
            if(!response.isSuccessful()&&!(allowNotFoundBody&&response.code()==404&&!body.isBlank())){
                LOGGER.error("Znack API request failed. method={}, url={}, httpStatus={}, contentType={}, responseBody={}",
                        request.method(),request.url(),response.code(),response.header("Content-Type",""),
                        ZnackSanitizer.diagnostic(body));
                throw new ZnackApiException("Znack API request failed",response.code(),body);
            }
            if(body.isBlank())return JsonNull.INSTANCE;
            try{
                return JsonParser.parseString(body);
            }catch(JsonParseException e){
                LOGGER.error("Znack API returned invalid JSON. method={}, url={}, httpStatus={}, contentType={}, responseBody={}",
                        request.method(),request.url(),response.code(),response.header("Content-Type",""),
                        ZnackSanitizer.diagnostic(body),e);
                throw new IOException("Znack API returned invalid JSON (HTTP "+response.code()+"): "
                        +ZnackSanitizer.message(body),e);
            }
        }
    }
    private String documentId(JsonElement response){
        if(response==null||response.isJsonNull())return "";
        if(response.isJsonPrimitive())return response.getAsString().trim();
        if(!response.isJsonObject())return "";
        JsonObject object=response.getAsJsonObject();
        for(String key:new String[]{"uuid","document_id","documentId","id"})
            if(object.has(key)&&!object.get(key).isJsonNull()&&object.get(key).isJsonPrimitive())
                return object.get(key).getAsString().trim();
        return "";
    }
    private static String join(String base,String path){return base.replaceAll("/+$","")+path;}
    private static String url(String v){return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);}
    static String apiRoot(String base) {
        return base.replaceAll("/api/v\\d+/(?:true-api|lk)/?$", "").replaceAll("/+$", "");
    }
    static String authBase(String base) { return trueApiBase(base, 3); }
    static String trueApiBase(String base, int version) { return apiRoot(base) + "/api/v" + version + "/true-api"; }

    public static class ZnackApiException extends IOException {
        private final int statusCode;
        public ZnackApiException(String message,int statusCode,String body){super(message+" (HTTP "+statusCode+"): "+ZnackSanitizer.message(body));this.statusCode=statusCode;}
        public int statusCode(){return statusCode;}
    }
}
