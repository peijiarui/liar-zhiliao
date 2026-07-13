package org.liar.zhiliao.ingestion.service;

import org.liar.zhiliao.ingestion.records.ParentChildSplitResult;

/**
 * Document splitting interface.
 * MVP: RecursiveDocumentSplitter (recursive split by paragraphs → sentences)
 * Future: SemanticDocumentSplitter (embedding similarity breakpoints)
 *         ParentChildDocumentSplitter (Parent 2048t + Child 512t)
 */
public interface RecursiveDocumentSplitter {
    ParentChildSplitResult split(String text, String documentId);
}
