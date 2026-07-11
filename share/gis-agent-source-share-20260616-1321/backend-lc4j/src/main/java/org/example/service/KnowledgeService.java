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
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class KnowledgeService {
    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    public void ingestDocument(MultipartFile file) throws IOException {
        DocumentSource source = new DocumentSource() {
            @Override
            public InputStream inputStream() throws IOException {
                return file.getInputStream();
            }

            @Override
            public Metadata metadata() {
                return Metadata.from("file_name", file.getOriginalFilename());
            }
        };
        DocumentParser parser = file.getOriginalFilename().endsWith(".pdf")
                ? new ApachePdfBoxDocumentParser()
                : new TextDocumentParser();

        Document document = DocumentLoader.load(source, parser);
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = splitter.split(document);
        String fileName = file.getOriginalFilename();
        for (TextSegment segment : segments) {
            segment.metadata().put("file_name", fileName);
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        System.out.println("已向量化并保存 " + fileName + " 的 " + segments.size() + " 段内容。");
    }

    /**
     * 检索知识库
     */
    public String search(String query, int maxResults) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> matches = 
                embeddingStore.findRelevant(queryEmbedding, maxResults, 0.5);
            
            if (matches.isEmpty()) {
                return "未找到相关知识库内容";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("检索到 ").append(matches.size()).append(" 条相关内容：\n");
            
            for (int i = 0; i < matches.size(); i++) {
                var match = matches.get(i);
                TextSegment segment = match.embedded();
                double score = match.score();
                String fileName = segment.metadata().getString("file_name");
                
                result.append("\n").append(i + 1).append(". ");
                if (fileName != null) {
                    result.append("【").append(fileName).append("】");
                }
                result.append(" (相关度: ").append(String.format("%.2f", score)).append(")\n");
                result.append(segment.text());
            }
            
            return result.toString();
        } catch (Exception e) {
            return "知识库检索失败: " + e.getMessage();
        }
    }
}
