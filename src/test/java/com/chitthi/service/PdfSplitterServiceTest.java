package com.chitthi.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfSplitterServiceTest {

    private PdfSplitterService pdfSplitterService;

    @BeforeEach
    void setUp() {
        pdfSplitterService = new PdfSplitterService();
    }

    private byte[] createTestPdf(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(100, 700);
                    stream.showText("Page " + i + " test content");
                    stream.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    void splitPdf_with12Pages_shouldCreate2Batches() throws Exception {
        byte[] pdfBytes = createTestPdf(12);

        PdfSplitterService.SplitResult result = pdfSplitterService.splitPdf(pdfBytes);

        assertThat(result.totalPages()).isEqualTo(12);
        assertThat(result.pages()).hasSize(12);
        assertThat(result.batches()).hasSize(2);

        PdfSplitterService.PdfBatchData batch1 = result.batches().get(0);
        assertThat(batch1.startPage()).isEqualTo(1);
        assertThat(batch1.endPage()).isEqualTo(10);
        assertThat(batch1.pageRange()).isEqualTo("1-10");
        assertThat(batch1.pdfBytes()).isNotEmpty();

        PdfSplitterService.PdfBatchData batch2 = result.batches().get(1);
        assertThat(batch2.startPage()).isEqualTo(11);
        assertThat(batch2.endPage()).isEqualTo(12);
        assertThat(batch2.pageRange()).isEqualTo("11-12");
        assertThat(batch2.pdfBytes()).isNotEmpty();

        for (PdfSplitterService.PageImageData pageImage : result.pages()) {
            assertThat(pageImage.imageBytes()).isNotEmpty();
            assertThat(pageImage.contentType()).isEqualTo("image/png");
        }
    }

    @Test
    void splitPdf_withSinglePage_shouldCreate1Batch() throws Exception {
        byte[] pdfBytes = createTestPdf(1);

        PdfSplitterService.SplitResult result = pdfSplitterService.splitPdf(pdfBytes);

        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.pages()).hasSize(1);
        assertThat(result.batches()).hasSize(1);
        assertThat(result.batches().get(0).pageRange()).isEqualTo("1-1");
    }

    @Test
    void splitPdf_exceeding30Pages_shouldThrowException() throws Exception {
        byte[] pdfBytes = createTestPdf(31);

        assertThatThrownBy(() -> pdfSplitterService.splitPdf(pdfBytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeding maximum allowed limit of 30");
    }
}
