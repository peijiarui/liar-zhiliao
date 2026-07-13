package org.liar.zhiliao.ingestion.service;

import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


public interface DocumentService {

    ZlDocument upload(MultipartFile file, Long kbId);

    ZlDocument getDocument(Long id);

    List<ZlDocument> listDocuments(Long kbId, Integer page, Integer pageSize);
}
