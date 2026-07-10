package org.liar.zhiliao.ingestion.service.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.ingestion.service.RecursiveDocumentSplitter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecursiveDocumentSplitterImpl implements RecursiveDocumentSplitter {

    private final DocumentSplitter documentSplitter;

    @Override
    public List<TextSegment> split(String text, String documentId) {
        Document document = Document.from(text, Metadata.from("documentId", documentId));
        return documentSplitter.split(document);
    }
}
