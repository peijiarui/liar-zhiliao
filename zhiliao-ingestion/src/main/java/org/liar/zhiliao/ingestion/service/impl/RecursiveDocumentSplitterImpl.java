package org.liar.zhiliao.ingestion.service.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.liar.zhiliao.ingestion.service.DocumentSplitter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecursiveDocumentSplitterImpl implements DocumentSplitter {

    private final dev.langchain4j.data.document.DocumentSplitter splitter;

    public RecursiveDocumentSplitterImpl() {
        this.splitter = DocumentSplitters.recursive(500, 100);
    }

    @Override
    public List<TextSegment> split(String text, String documentId) {
        Document document = Document.from(text, Metadata.from("documentId", documentId));
        return splitter.split(document);
    }
}
