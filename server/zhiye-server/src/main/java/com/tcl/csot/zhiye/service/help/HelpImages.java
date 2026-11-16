package com.tcl.csot.zhiye.service.help;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Set;

/** 把图片素材转成发给模型的 data URL：超过 4 MB 或边长过大时缩小并转成 JPEG。 */
final class HelpImages {
    static final long MAX_BYTES = 4L * 1024 * 1024;
    static final int MAX_SIDE = 2048;
    static final int TARGET_SIDE = 1600;
    private static final Set<String> PASS_THROUGH = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private HelpImages() {}

    /** 无法处理（如 SVG、过大且无法解码）时返回 null */
    static String dataUrl(String mime, byte[] data) {
        if (data == null || data.length == 0 || mime == null) return null;
        String m = mime.toLowerCase();
        if (m.equals("image/jpg")) m = "image/jpeg";
        if (!m.startsWith("image/") || m.contains("svg")) return null;
        BufferedImage img = null;
        try { img = ImageIO.read(new ByteArrayInputStream(data)); } catch (Exception ignored) { }
        boolean small = data.length <= MAX_BYTES;
        if (img == null) return small && PASS_THROUGH.contains(m) ? url(m, data) : null;
        if (small && PASS_THROUGH.contains(m) && Math.max(img.getWidth(), img.getHeight()) <= MAX_SIDE) return url(m, data);
        try {
            double k = Math.min(1.0, (double) TARGET_SIDE / Math.max(img.getWidth(), img.getHeight()));
            int w = Math.max(1, (int) Math.round(img.getWidth() * k)), h = Math.max(1, (int) Math.round(img.getHeight() * k));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            for (float q : new float[]{0.85f, 0.7f, 0.5f}) {
                byte[] jpg = jpeg(out, q);
                if (jpg.length <= MAX_BYTES) return url("image/jpeg", jpg);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bo)) {
            w.setOutput(out);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bo.toByteArray();
    }

    private static String url(String mime, byte[] data) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
    }
}
