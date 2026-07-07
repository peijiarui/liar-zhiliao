package org.liar.zhiliao.ingestion.service;

import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.springframework.web.multipart.MultipartFile;


public interface DocumentService {

    ZlDocument upload(MultipartFile file, Long kbId);

    ZlDocument getDocument(Long id);
}
