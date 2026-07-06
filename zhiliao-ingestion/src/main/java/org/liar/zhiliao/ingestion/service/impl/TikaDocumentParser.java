package org.liar.zhiliao.ingestion.service.impl;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.liar.zhiliao.ingestion.service.DocumentParser;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class TikaDocumentParser implements DocumentParser {

    private final Tika tika = new Tika();

    @Override
    public String parse(InputStream inputStream, String fileName) throws Exception {
        try {
            String text = tika.parseToString(inputStream);
            if (text == null || text.trim().isEmpty()) {
                throw new TikaException("No text content extracted from: " + fileName);
            }
            return text.trim();
        } catch (TikaException e) {
            throw new TikaException("Failed to parse document: " + fileName + " - " + e.getMessage());
        }
    }
}
