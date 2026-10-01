package com.lody.virtual.server.mediastore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Luca Boscolo Meneguolo @calugj
 */
class MediaStoreFileParser {

    private static final String TAG = MediaStoreFileParser.class.getSimpleName();

    private static final String ID = "id";
    private static final String OWNER = "owner";

    public MediaStoreFileParser() {}

    /**
     * Read data from file
     */
    public Map<Integer, Integer> read(final InputStream fileStream)
            throws IOException, JSONException {
        final var cache = new HashMap<Integer, Integer>();

        final var bytes = new byte[fileStream.available()];
        fileStream.read(bytes);
        final String content = new String(bytes, StandardCharsets.UTF_8);

        JSONArray jsonArray = new JSONArray(content);
        for (int i = 0; i < jsonArray.length(); i++) {
            final JSONObject jsonObject = jsonArray.getJSONObject(i);

            cache.put(Integer.valueOf(jsonObject.getString(ID)),
                    Integer.valueOf(jsonObject.getString(OWNER)));
        }
        return cache;
    }

    /**
     * Write data to file
     */
    public void write(final Map<Integer, Integer> cache, final OutputStream fileStream)
            throws IOException, JSONException {

        final JSONArray jsonArray = new JSONArray();
        for(int key : cache.keySet()) {
            final JSONObject jsonObject = new JSONObject();
            jsonObject.put(ID, key);
            jsonObject.put(OWNER, cache.get(key));

            jsonArray.put(jsonObject);
        }
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        byteStream.write(jsonArray.toString().getBytes());
        byteStream.writeTo(fileStream);
    }
}
