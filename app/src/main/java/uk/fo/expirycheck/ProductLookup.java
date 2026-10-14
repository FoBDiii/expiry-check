package uk.fo.expirycheck;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a product's name and photo from its barcode using free public databases:
 * Open Food Facts (groceries), Open Beauty Facts (toiletries), Open Pet Food Facts,
 * Open Products Facts (everything else), then UPCitemdb as a last resort.
 * The photo is downloaded once and kept on the phone so it shows offline.
 */
final class ProductLookup {
    private static final String UA = "ExpiryCheck/1.0 (Android; shop expiry tracker)";
    private static final String FIELDS = "product_name,product_name_en,generic_name,brands,quantity,"
            + "image_front_url,image_url,image_front_small_url";
    private static final String[][] OPEN_DBS = {
            {"https://world.openfoodfacts.org", "Open Food Facts"},
            {"https://world.openbeautyfacts.org", "Open Beauty Facts"},
            {"https://world.openpetfoodfacts.org", "Open Pet Food Facts"},
            {"https://world.openproductsfacts.org", "Open Products Facts"},
    };

    private ProductLookup() {
    }

    /** Returns {found, name, brand, quantity, image, source} or {found:false, offline?:true}. */
    static JSONObject lookup(Context ctx, String code) {
        JSONObject out = new JSONObject();
        boolean anyResponse = false;
        try {
            out.put("found", false);
            List<String> codes = candidates(code);
            if (codes.isEmpty()) return out; // not a product barcode (e.g. a QR code): nothing to look up
            for (String candidate : codes) {
                for (String[] db : OPEN_DBS) {
                    String body = get(db[0] + "/api/v2/product/" + candidate + "?fields=" + FIELDS);
                    if (body == null) continue;
                    anyResponse = true;
                    if (body.isEmpty()) continue;
                    JSONObject j = new JSONObject(body);
                    if (j.optInt("status") != 1) continue;
                    JSONObject p = j.optJSONObject("product");
                    if (p == null) continue;
                    String name = first(p, "product_name_en", "product_name", "generic_name");
                    String img = first(p, "image_front_url", "image_url", "image_front_small_url");
                    if (name.isEmpty() && img.isEmpty()) continue;
                    return result(ctx, name, firstBrand(p.optString("brands")), p.optString("quantity").trim(), img, db[1]);
                }
            }
            // UPCitemdb free tier: general merchandise, limited to about 100 lookups a day.
            String body = get("https://api.upcitemdb.com/prod/trial/lookup?upc=" + codes.get(0));
            if (body != null) {
                anyResponse = true;
                if (!body.isEmpty()) {
                    JSONArray items = new JSONObject(body).optJSONArray("items");
                    if (items != null && items.length() > 0) {
                        JSONObject it = items.getJSONObject(0);
                        JSONArray imgs = it.optJSONArray("images");
                        String img = imgs != null && imgs.length() > 0 ? imgs.optString(0) : "";
                        String name = it.optString("title").trim();
                        if (!name.isEmpty()) {
                            return result(ctx, name, it.optString("brand").trim(), "", img, "UPCitemdb");
                        }
                    }
                }
            }
            if (!anyResponse) out.put("offline", true);
        } catch (Exception e) {
            try {
                out.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** UPC-A codes are also stored as EAN-13 with a leading 0, and the other way round. */
    private static List<String> candidates(String code) {
        List<String> list = new ArrayList<>();
        String c = code == null ? "" : code.trim();
        if (!c.matches("\\d{6,14}")) return list;
        list.add(c);
        if (c.length() == 12) list.add("0" + c);
        if (c.length() == 13 && c.startsWith("0")) list.add(c.substring(1));
        return list;
    }

    private static JSONObject result(Context ctx, String name, String brand, String qty, String imgUrl, String source)
            throws Exception {
        JSONObject r = new JSONObject();
        r.put("found", true);
        r.put("name", name);
        r.put("brand", brand);
        r.put("quantity", qty);
        r.put("source", source);
        String image = downloadImage(ctx, imgUrl);
        if (image != null) r.put("image", image);
        return r;
    }

    private static String downloadImage(Context ctx, String url) {
        if (url == null || !url.startsWith("http")) return null;
        try {
            byte[] bytes = getBytes(url);
            if (bytes == null) return null;
            File dir = new File(ctx.getFilesDir(), "img");
            dir.mkdirs();
            String name = MainActivity.newImageName();
            return MainActivity.writeScaledJpeg(bytes, new File(dir, name)) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(JSONObject p, String... keys) {
        for (String k : keys) {
            String v = p.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String firstBrand(String brands) {
        if (brands == null) return "";
        int comma = brands.indexOf(',');
        return (comma >= 0 ? brands.substring(0, comma) : brands).trim();
    }

    /** Body text; "" when the server answered but had nothing (e.g. 404); null when unreachable. */
    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            int code = c.getResponseCode();
            if (code == 404) return "";
            if (code != 200) return code == 429 ? "" : null;
            return new String(readAll(c.getInputStream()), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) return null;
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, image/*");
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8_000_000) break;
            }
            return bos.toByteArray();
        }
    }
}
