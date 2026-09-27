package com.chitthi.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfSplitterService {

    private static final Logger log = LoggerFactory.getLogger(PdfSplitterService.class);
    public static final int MAX_PAGES_LIMIT = 30;
    public static final int MAX_PAGES_PER_BATCH = 10;
    public static final float PREVIEW_DPI = 150.0f;

    public record PageImageData(int pageNo, byte[] imageBytes, String contentType) {}
    public record PdfBatchData(int startPage, int endPage, String pageRange, byte[] pdfBytes) {}
    public record SplitResult(int totalPages, List<PageImageData> pages, List<PdfBatchData> batches) {}

    /**
     * Inspects, validates, and splits a multi-page PDF into batches of <=10 pages
     * and renders per-page PNG images.
     */
    public SplitResult splitPdf(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            log.info("Inspecting PDF: total pages = {}", totalPages);

            if (totalPages <= 0) {
                throw new IllegalArgumentException("PDF contains no pages");
            }
            if (totalPages > MAX_PAGES_LIMIT) {
                throw new IllegalArgumentException("Document has " + totalPages + " pages, exceeding maximum allowed limit of " + MAX_PAGES_LIMIT);
            }

            PDFRenderer renderer = new PDFRenderer(document);
            List<PageImageData> pageImages = new ArrayList<>(totalPages);

            // Render per-page preview images
            for (int i = 0; i < totalPages; i++) {
                int pageNo = i + 1;
                BufferedImage bufferedImage = renderer.renderImageWithDPI(i, PREVIEW_DPI, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bufferedImage, "PNG", baos);
                pageImages.add(new PageImageData(pageNo, baos.toByteArray(), "image/png"));
            }

            // Partition into batches of <= 10 pages
            List<PdfBatchData> batches = new ArrayList<>();
            for (int start = 1; start <= totalPages; start += MAX_PAGES_PER_BATCH) {
                int end = Math.min(start + MAX_PAGES_PER_BATCH - 1, totalPages);
                String pageRange = start + "-" + end;

                try (PDDocument subDoc = new PDDocument()) {
                    for (int p = start - 1; p < end; p++) {
                        subDoc.importPage(document.getPage(p));
                    }
                    ByteArrayOutputStream subBaos = new ByteArrayOutputStream();
                    subDoc.save(subBaos);
                    batches.add(new PdfBatchData(start, end, pageRange, subBaos.toByteArray()));
                }
            }

            log.info("PDF successfully split into {} pages and {} batches", totalPages, batches.size());
            return new SplitResult(totalPages, pageImages, batches);
        }
    }
}
