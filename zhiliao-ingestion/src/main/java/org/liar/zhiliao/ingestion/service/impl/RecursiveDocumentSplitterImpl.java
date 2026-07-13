package org.liar.zhiliao.ingestion.service.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.ingestion.records.ParentChildSplitResult;
import org.liar.zhiliao.ingestion.service.RecursiveDocumentSplitter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecursiveDocumentSplitterImpl implements RecursiveDocumentSplitter {

    private final DocumentSplitter parentSplitter;
    private final DocumentSplitter childSplitter;

    @Override
    public ParentChildSplitResult split(String text, String documentId) {
        Document doc = Document.from(text);

        // Step 1: 分割为 parent chunks (2048 token)
        List<TextSegment> parents = parentSplitter.split(doc);

        // Step 2: 每个 parent 再分割为 children (512 token)
        List<TextSegment> allChildren = new ArrayList<>();
        List<Integer> mappingList = new ArrayList<>();

        for (int pIdx = 0; pIdx < parents.size(); pIdx++) {
            Document parentDoc = Document.from(parents.get(pIdx).text());
            List<TextSegment> children = childSplitter.split(parentDoc);
            for (TextSegment child : children) {
                allChildren.add(child);
                mappingList.add(pIdx);
            }
        }

        int[] mappingArray = mappingList.stream().mapToInt(Integer::intValue).toArray();
        return new ParentChildSplitResult(parents, allChildren, mappingArray);
    }
}
