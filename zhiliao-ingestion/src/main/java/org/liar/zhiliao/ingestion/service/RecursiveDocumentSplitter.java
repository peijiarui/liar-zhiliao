package org.liar.zhiliao.ingestion.service;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * Document splitting interface.
 * MVP: RecursiveDocumentSplitter (recursive split by paragraphs → sentences)
 * Future: SemanticDocumentSplitter (embedding similarity breakpoints)
 *         ParentChildDocumentSplitter (Parent 2048t + Child 512t)
 */
public interface RecursiveDocumentSplitter {
    List<TextSegment> split(String text, String documentId);
}
