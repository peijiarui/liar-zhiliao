package org.liar.zhiliao.ingestion.service;

import org.liar.zhiliao.ingestion.model.Document;
import org.springframework.web.multipart.MultipartFile;


public interface DocumentService {

    Document upload(MultipartFile file, Long kbId);

    Document getDocument(Long id);
}
