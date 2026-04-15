package org.example.service;

import dev.langchain4j.data.document.*;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class KnowledgeService {
    @Autowired
    private EmbeddingModel embeddingModel; // 阿里云 DashScope 或 OpenAI 的嵌入模型

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore; // 指向 pgvector

    public void ingestDocument(MultipartFile file) throws IOException {
        // 1. 加载文档 (支持 PDF/Text/Markdown)
        DocumentSource source = new DocumentSource() {
            @Override
            public InputStream inputStream() throws IOException {
                return file.getInputStream();
            }

            @Override
            public Metadata metadata() {
                // 将文件名存入元数据，方便 AI 溯源
                return Metadata.from("file_name", file.getOriginalFilename());
            }
        };
        DocumentParser parser = new TextDocumentParser();
        if (file.getOriginalFilename().endsWith(".pdf")) {
            // parser = new ApachePdfDocumentParser(); // 需要引入 langchain4j-document-parser-apache-pdf
        }

        // 3. 加载文档
        Document document = DocumentLoader.load(source, parser);
        // 2. 切分文档 (每块 500 字，重叠 50 字保持上下文)
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = splitter.split(document);

        // 3. 向量化并存储
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        String fileName = file.getOriginalFilename();
        System.out.println("已向量化并保存 " + fileName + " 的 " + segments.size() + " 段内容。");
    }
}
