package com.chitthi.service;

import com.chitthi.config.PreprocessingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Pure Java image pre-processing pipeline for handwritten Indic heritage letters (FR12).
 * Implements:
 * 1. Dominant angle skew detection via projection profile variance & affine rotation
 * 2. Auto-contrast stretching via luminance histogram percentile clipping
 * 3. Edge sharpening for faded handwriting strokes & Devanagari ligatures
 */
@Service
public class ImagePreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(ImagePreprocessingService.class);
    private final PreprocessingProperties properties;

    public record PreprocessResult(
            BufferedImage image,
            byte[] imageBytes,
            double detectedSkewAngle,
            boolean contrastEnhanced,
            boolean sharpened
    ) {}

    public ImagePreprocessingService(PreprocessingProperties properties) {
        this.properties = properties != null ? properties : new PreprocessingProperties();
    }

    /**
     * Preprocesses raw image bytes (PNG, JPEG).
     */
    public PreprocessResult preprocess(byte[] imageBytes, String contentType) throws IOException {
        if (!properties.enabled() || imageBytes == null || imageBytes.length == 0) {
            BufferedImage image = (imageBytes != null && imageBytes.length > 0)
                    ? ImageIO.read(new ByteArrayInputStream(imageBytes)) : null;
            return new PreprocessResult(image, imageBytes, 0.0, false, false);
        }

        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (original == null) {
            log.warn("Unable to decode image bytes for pre-processing, returning raw bytes");
            return new PreprocessResult(null, imageBytes, 0.0, false, false);
        }

        return preprocess(original);
    }

    /**
     * Preprocesses a BufferedImage applying deskew, contrast stretching, and sharpening.
     */
    public PreprocessResult preprocess(BufferedImage input) {
        if (!properties.enabled() || input == null) {
            return new PreprocessResult(input, toPngBytes(input), 0.0, false, false);
        }

        BufferedImage current = toRgbImage(input);
        double detectedAngle = 0.0;
        boolean contrastApplied = false;
        boolean sharpenedApplied = false;

        // 1. Deskew Filter
        if (properties.deskew()) {
            detectedAngle = detectSkewAngle(current, properties.maxSkewAngle());
            if (Math.abs(detectedAngle) >= 0.5) {
                log.info("Correcting detected handwriting skew angle: {}°", String.format("%.2f", detectedAngle));
                current = rotateImage(current, -detectedAngle);
            }
        }

        // 2. Auto-Contrast Stretching
        if (properties.autoContrast()) {
            current = enhanceContrast(current);
            contrastApplied = true;
        }

        // 3. Stroke Sharpening
        if (properties.sharpen()) {
            current = sharpenStrokes(current);
            sharpenedApplied = true;
        }

        byte[] pngBytes = toPngBytes(current);
        return new PreprocessResult(current, pngBytes, detectedAngle, contrastApplied, sharpenedApplied);
    }

    /**
     * Estimates the dominant skew angle using horizontal projection profile variance.
     * Uses a scaled-down luminance copy for sub-50ms execution.
     */
    public double detectSkewAngle(BufferedImage image, double maxAngle) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Downscale to max 600px width/height for fast variance sampling
        double scale = Math.min(1.0, 600.0 / Math.max(width, height));
        int scaledW = Math.max(10, (int) (width * scale));
        int scaledH = Math.max(10, (int) (height * scale));

        BufferedImage scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(image, 0, 0, scaledW, scaledH, null);
        g2d.dispose();

        // Extract grayscale values and compute global Otsu/mean threshold for ink vs background
        int[][] gray = new int[scaledW][scaledH];
        long sumLuminance = 0;
        for (int y = 0; y < scaledH; y++) {
            for (int x = 0; x < scaledW; x++) {
                int val = scaled.getRaster().getSample(x, y, 0);
                gray[x][y] = val;
                sumLuminance += val;
            }
        }
        int avgThreshold = (int) (sumLuminance / (scaledW * scaledH));
        // Ink is typically darker than paper background
        int inkThreshold = Math.max(40, avgThreshold - 15);

        double bestAngle = 0.0;
        double maxVariance = -1.0;
        double step = 0.5;

        int cx = scaledW / 2;
        int cy = scaledH / 2;

        for (double angle = -maxAngle; angle <= maxAngle; angle += step) {
            double rad = Math.toRadians(angle);
            double sin = Math.sin(rad);
            double cos = Math.cos(rad);

            int[] projection = new int[scaledH];

            for (int y = 0; y < scaledH; y++) {
                int dy = y - cy;
                for (int x = 0; x < scaledW; x++) {
                    if (gray[x][y] < inkThreshold) {
                        int dx = x - cx;
                        int rotatedY = (int) (-dx * sin + dy * cos) + cy;
                        if (rotatedY >= 0 && rotatedY < scaledH) {
                            projection[rotatedY]++;
                        }
                    }
                }
            }

            // Calculate variance of horizontal projection profile
            double mean = 0.0;
            for (int val : projection) {
                mean += val;
            }
            mean /= scaledH;

            double variance = 0.0;
            for (int val : projection) {
                double diff = val - mean;
                variance += diff * diff;
            }

            if (variance > maxVariance) {
                maxVariance = variance;
                bestAngle = angle;
            }
        }

        return bestAngle;
    }

    /**
     * Rotates image around its center with high-quality bilinear interpolation and white background.
     */
    public BufferedImage rotateImage(BufferedImage image, double angleDegrees) {
        double rad = Math.toRadians(angleDegrees);
        int w = image.getWidth();
        int h = image.getHeight();

        BufferedImage rotated = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fill background with white
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        AffineTransform at = AffineTransform.getRotateInstance(rad, w / 2.0, h / 2.0);
        g.setTransform(at);
        g.drawImage(image, 0, 0, null);
        g.dispose();

        return rotated;
    }

    /**
     * Enhances contrast by clipping 1st and 99th percentiles of the luminance histogram
     * and stretching dynamic range, making faded ink deep and dark while brightening yellowed paper.
     */
    public BufferedImage enhanceContrast(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        int[] rgbData = image.getRGB(0, 0, w, h, null, 0, w);
        int[] histogram = new int[256];

        for (int rgb : rgbData) {
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            histogram[lum]++;
        }

        int totalPixels = w * h;
        int p1 = (int) (totalPixels * 0.01);
        int p99 = (int) (totalPixels * 0.99);

        int minLum = 0;
        int cum = 0;
        for (int i = 0; i < 256; i++) {
            cum += histogram[i];
            if (cum >= p1) {
                minLum = i;
                break;
            }
        }

        int maxLum = 255;
        cum = 0;
        for (int i = 0; i < 256; i++) {
            cum += histogram[i];
            if (cum >= p99) {
                maxLum = i;
                break;
            }
        }

        // Avoid division by zero on uniform images
        if (maxLum - minLum < 10) {
            return image;
        }

        float scale = 255.0f / (maxLum - minLum);

        BufferedImage enhanced = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outRgb = new int[totalPixels];

        for (int i = 0; i < totalPixels; i++) {
            int rgb = rgbData[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            int newR = clamp((int) ((r - minLum) * scale));
            int newG = clamp((int) ((g - minLum) * scale));
            int newB = clamp((int) ((b - minLum) * scale));

            outRgb[i] = (newR << 16) | (newG << 8) | newB;
        }

        enhanced.setRGB(0, 0, w, h, outRgb, 0, w);
        return enhanced;
    }

    /**
     * Sharpens ink strokes using an unsharp mask convolution kernel, enhancing cursive
     * ligatures and Indic vowel diacritics.
     */
    public BufferedImage sharpenStrokes(BufferedImage image) {
        float[] kernelData = {
                0.0f, -0.5f,  0.0f,
               -0.5f,  3.0f, -0.5f,
                0.0f, -0.5f,  0.0f
        };
        Kernel kernel = new Kernel(3, 3, kernelData);
        ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);

        BufferedImage sharpened = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        op.filter(image, sharpened);
        return sharpened;
    }

    private BufferedImage toRgbImage(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, Color.WHITE, null);
        g.dispose();
        return rgb;
    }

    private byte[] toPngBytes(BufferedImage image) {
        if (image == null) return new byte[0];
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("Failed to encode preprocessed image to PNG", e);
            return new byte[0];
        }
    }

    private static int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }
}
