package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.dto.StickerResponse;
import com.tuandev.fbsbarcode.models.Sticker;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WbStickerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbStickerService.class);
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final int BATCH_SIZE = 100;

    public List<Sticker> getStickers(String apiKey, List<Long> orderIds) throws IOException {
        List<Sticker> allStickers = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i += BATCH_SIZE) {
            List<Long> batch = orderIds.subList(i, Math.min(i + BATCH_SIZE, orderIds.size()));
            allStickers.addAll(requestStickerBatch(apiKey, batch));
        }
        return allStickers;
    }

    private List<Sticker> requestStickerBatch(String apiKey, List<Long> orderIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/stickers?type=png&width=58&height=40";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("orders", orderIds);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                GSON.toJson(bodyMap)
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                StickerResponse stickerResponse = GSON.fromJson(response.body().string(), StickerResponse.class);
                return stickerResponse.getStickers();
            }

            String responseBody = response.body() == null ? "" : response.body().string();
            LOGGER.warn("WB sticker request failed with status {} and body {}", response.code(), responseBody);
            return Collections.emptyList();
        }
    }
}
