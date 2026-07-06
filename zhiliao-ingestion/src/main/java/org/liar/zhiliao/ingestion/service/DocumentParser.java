package org.liar.zhiliao.ingestion.service;

import java.io.InputStream;

/**
 * Document parsing interface.
 * MVP: TikaDocumentParser (Apache Tika for universal parsing)
 * Future: OcrDocumentParser (Tesseract OCR), PdfBoxDocumentParser (complex PDF fallback)
 */
public interface DocumentParser {
    String parse(InputStream inputStream, String fileName) throws Exception;
}
