package com.qinqing.bangbang;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class NetworkClient {
    private NetworkClient() {
    }

    static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static JSONObject postJson(String baseUrl, String path, JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = open(baseUrl, path, "POST", "application/json");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        return readJson(conn);
    }

    static JSONObject getJson(String baseUrl, String path) throws Exception {
        return readJson(open(baseUrl, path, "GET", null));
    }

    static void postJpeg(String baseUrl, String path, byte[] jpeg) throws Exception {
        HttpURLConnection conn = open(baseUrl, path, "POST", "image/jpeg");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(jpeg.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(jpeg);
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
    }

    static Bitmap getJpeg(String baseUrl, String path) throws Exception {
        HttpURLConnection conn = open(baseUrl, path, "GET", null);
        int code = conn.getResponseCode();
        if (code == 204 || code == 404) {
            return null;
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        }
    }

    private static HttpURLConnection open(String baseUrl, String path, String method, String contentType) throws Exception {
        URL url = new URL(normalizeBaseUrl(baseUrl) + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2500);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }
        return conn;
    }

    private static JSONObject readJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream source = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(source);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }
}
