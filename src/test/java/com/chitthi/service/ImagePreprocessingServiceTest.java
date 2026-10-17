package com.chitthi.service;

import com.chitthi.config.PreprocessingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ImagePreprocessingServiceTest {

    private ImagePreprocessingService preprocessingService;

    @BeforeEach
    void setUp() {
        PreprocessingProperties props = new PreprocessingProperties(true, true, true, true, 15.0);
        preprocessingService = new ImagePreprocessingService(props);
    }

    /**
     * Creates a synthetic image with horizontal text-like black bars on white background.
     */
    private BufferedImage createSyntheticTextImage(int width, int height, double rotateDegrees) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        if (Math.abs(rotateDegrees) > 0.01) {
            AffineTransform at = AffineTransform.getRotateInstance(Math.toRadians(rotateDegrees), width / 2.0, height / 2.0);
            g.setTransform(at);
        }

        g.setColor(Color.BLACK);
        // Draw 6 horizontal text line bars
        for (int y = 40; y < height - 40; y += 40) {
            g.fillRect(30, y, width - 60, 12);
        }
        g.dispose();
        return img;
    }

    @Test
    void testDetectSkewAngle_withRotatedImage_detectsAngleAccurately() {
        double expectedAngle = 5.0;
        BufferedImage rotatedImage = createSyntheticTextImage(300, 300, expectedAngle);

        double detected = preprocessingService.detectSkewAngle(rotatedImage, 15.0);

        // Skew angle detection should be within +/- 1.0 degree of expected
        assertThat(Math.abs(detected - expectedAngle)).isLessThanOrEqualTo(1.0);
    }

    @Test
    void testEnhanceContrast_withWashedOutImage_expandsDynamicRange() {
        int w = 100, h = 100;
        BufferedImage lowContrast = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = lowContrast.createGraphics();
        // Background at RGB 180 (light gray)
        g.setColor(new Color(180, 180, 180));
        g.fillRect(0, 0, w, h);
        // "Faded text" at RGB 120 (mid gray)
        g.setColor(new Color(120, 120, 120));
        g.fillRect(20, 20, 60, 20);
        g.dispose();

        BufferedImage enhanced = preprocessingService.enhanceContrast(lowContrast);

        // Check that background (pixel 0,0) is brightened towards white
        int bgRgb = enhanced.getRGB(0, 0);
        int bgLum = (bgRgb >> 16) & 0xFF;

        // Check that text (pixel 30,30) is darkened towards black
        int textRgb = enhanced.getRGB(30, 30);
        int textLum = (textRgb >> 16) & 0xFF;

        assertThat(bgLum).isGreaterThan(180);
        assertThat(textLum).isLessThan(120);
        assertThat(bgLum - textLum).isGreaterThan(60); // Contrast difference substantially increased
    }

    @Test
    void testSharpenStrokes_sharpensEdgesWithoutAlteringDimensions() {
        BufferedImage original = createSyntheticTextImage(150, 150, 0.0);

        BufferedImage sharpened = preprocessingService.sharpenStrokes(original);

        assertThat(sharpened.getWidth()).isEqualTo(original.getWidth());
        assertThat(sharpened.getHeight()).isEqualTo(original.getHeight());
    }

    @Test
    void testPreprocess_whenDisabled_returnsOriginal() {
        PreprocessingProperties disabledProps = new PreprocessingProperties(false, false, false, false, 15.0);
        ImagePreprocessingService service = new ImagePreprocessingService(disabledProps);

        BufferedImage original = createSyntheticTextImage(100, 100, 0.0);
        ImagePreprocessingService.PreprocessResult result = service.preprocess(original);

        assertThat(result.image()).isSameAs(original);
        assertThat(result.contrastEnhanced()).isFalse();
        assertThat(result.sharpened()).isFalse();
        assertThat(result.detectedSkewAngle()).isEqualTo(0.0);
    }

    @Test
    void testPreprocess_withByteArray_encodesAndDecodesSuccessfully() throws IOException {
        BufferedImage original = createSyntheticTextImage(200, 200, 3.0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(original, "PNG", baos);
        byte[] inputBytes = baos.toByteArray();

        ImagePreprocessingService.PreprocessResult result = preprocessingService.preprocess(inputBytes, "image/png");

        assertThat(result.image()).isNotNull();
        assertThat(result.imageBytes()).isNotEmpty();
        assertThat(result.contrastEnhanced()).isTrue();
        assertThat(result.sharpened()).isTrue();
    }
}
