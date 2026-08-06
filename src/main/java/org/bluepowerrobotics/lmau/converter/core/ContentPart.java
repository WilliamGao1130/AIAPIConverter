package org.bluepowerrobotics.lmau.converter.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 统一的多模态内容部件：文本或图片。
 * 图片可用 http(s) URL，也可用 data: URL（自动解析为字节数据）。
 */
public final class ContentPart {

    public enum Type {
        TEXT,
        IMAGE_URL
    }

    private final Type type;
    private final String text;
    private final String imageUrl;
    private final String mimeType;
    private final byte[] imageData;

    private ContentPart(Type type, String text, String imageUrl, String mimeType, byte[] imageData) {
        this.type = Objects.requireNonNull(type, "type");
        this.text = text;
        this.imageUrl = imageUrl;
        this.mimeType = mimeType;
        this.imageData = imageData;
    }

    public static ContentPart text(String text) {
        return new ContentPart(Type.TEXT, text, null, null, null);
    }

    /** 图片部件。支持普通 URL 与 data: URL。 */
    public static ContentPart imageUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("image url must not be null");
        }
        if (url.startsWith("data:")) {
            return parseDataUrl(url);
        }
        return new ContentPart(Type.IMAGE_URL, null, url, null, null);
    }

    private static ContentPart parseDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("Invalid data URL: " + dataUrl);
        }
        String meta = dataUrl.substring(5, comma); // 去掉 "data:"
        String payload = dataUrl.substring(comma + 1);
        String mime = "application/octet-stream";
        boolean base64 = false;
        for (String part : meta.split(";")) {
            if (part.isEmpty()) {
                continue;
            }
            if ("base64".equalsIgnoreCase(part)) {
                base64 = true;
            } else if (part.contains("/")) {
                mime = part;
            }
        }
        byte[] data;
        if (base64) {
            data = Base64.getDecoder().decode(payload);
        } else {
            data = payload.getBytes(StandardCharsets.UTF_8);
        }
        return new ContentPart(Type.IMAGE_URL, null, dataUrl, mime, data);
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    /** 原始图片 URL（http(s) 或 data URL）。 */
    public String getImageUrl() {
        return imageUrl;
    }

    public String getMimeType() {
        return mimeType;
    }

    /** data URL 解码后的图片字节；普通 URL 时为 null。 */
    public byte[] getImageData() {
        return imageData;
    }

    public boolean isImageDataAvailable() {
        return imageData != null;
    }

    @Override
    public String toString() {
        return type == Type.TEXT
                ? "ContentPart{TEXT, text='" + text + "'}"
                : "ContentPart{IMAGE_URL, url='" + imageUrl + "'}";
    }
}
