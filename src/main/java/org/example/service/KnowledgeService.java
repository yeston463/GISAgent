package org.example.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentLoader;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSource;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private ResourcePatternResolver resourceResolver;

    @Value("${rag.auto-load:true}")
    private boolean autoLoad;

    @Value("${rag.min-score:0.6}")
    private double minScore;

    private final Set<String> loadedResources = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private volatile String loadState = "not_started";
    private volatile String lastError = "";
    private volatile int bundledSegmentCount = 0;

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleBundledContentLoad() {
        if (!autoLoad) {
            loadState = "disabled";
            return;
        }
        Thread loader = new Thread(this::loadBundledContent, "rag-content-loader");
        loader.setDaemon(true);
        loader.start();
    }

    public void ingestDocument(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() == null ? "uploaded-document" : file.getOriginalFilename();
        DocumentSource source = source(fileName, file::getInputStream);
        int count = ingestSource(source, fileName);
        log.info("已向量化并保存 {} 的 {} 段内容", fileName, count);
    }

    public Map<String, Object> loadBundledContent() {
        if (!loading.compareAndSet(false, true)) {
            return getLoadStatus();
        }

        loadState = "loading";
        lastError = "";
        try {
            Resource[] resources = resourceResolver.getResources("classpath*:content/*.*");
            int addedSegments = 0;
            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null) {
                    continue;
                }
                String fileName = resource.getFilename();
                String resourceId = resource.getURL().toExternalForm();
                if (!isSupported(fileName) || loadedResources.contains(resourceId)) {
                    continue;
                }

                DocumentSource source = source(fileName, resource::getInputStream);
                int count = ingestSource(source, fileName);
                loadedResources.add(resourceId);
                addedSegments += count;
                log.info("RAG 自动加载完成: {} ({} 段)", fileName, count);
            }
            bundledSegmentCount += addedSegments;
            loadState = "loaded";
        } catch (Exception e) {
            loadState = "failed";
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("RAG 自动加载失败，将在后续检索时重试: {}", lastError);
        } finally {
            loading.set(false);
        }
        return getLoadStatus();
    }

    public Map<String, Object> getLoadStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", loadState);
        status.put("autoLoad", autoLoad);
        status.put("loading", loading.get());
        status.put("documents", loadedResources.size());
        status.put("segments", bundledSegmentCount);
        if (!lastError.isBlank()) {
            status.put("error", lastError);
        }
        return status;
    }

    public String search(String query, int maxResults) {
        if (autoLoad) {
            if (("not_started".equals(loadState) || "failed".equals(loadState)) && !loading.get()) {
                loadBundledContent();
            } else if (loading.get()) {
                waitForInitialLoad();
            }
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                    queryEmbedding,
                    Math.max(1, maxResults),
                    minScore
            );

            if (matches.isEmpty()) {
                return "未找到相关知识库内容";
            }

            StringBuilder result = new StringBuilder();
            result.append("检索到 ").append(matches.size()).append(" 条相关内容：\n");
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                TextSegment segment = match.embedded();
                String fileName = segment.metadata().getString("file_name");
                result.append("\n").append(i + 1).append(". ");
                if (fileName != null) {
                    result.append("【").append(fileName).append("】");
                }
                result.append(" (相关度: ")
                        .append(String.format(Locale.ROOT, "%.2f", match.score()))
                        .append(")\n")
                        .append(segment.text());
            }
            return result.toString();
        } catch (Exception e) {
            return "知识库检索失败: " + e.getMessage();
        }
    }

    private int ingestSource(DocumentSource source, String fileName) {
        DocumentParser parser = parserFor(fileName);
        Document document = DocumentLoader.load(source, parser);
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = new ArrayList<>(splitter.split(document));
        for (TextSegment segment : segments) {
            segment.metadata().put("file_name", fileName);
            segment.metadata().put("source", "knowledge-base");
        }
        if (segments.isEmpty()) {
            return 0;
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        return segments.size();
    }

    private void waitForInitialLoad() {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (loading.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private DocumentParser parserFor(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? new ApachePdfBoxDocumentParser()
                : new TextDocumentParser();
    }

    private boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".pdf");
    }

    private DocumentSource source(String fileName, InputStreamSupplier supplier) {
        return new DocumentSource() {
            @Override
            public InputStream inputStream() throws IOException {
                return supplier.open();
            }

            @Override
            public Metadata metadata() {
                return Metadata.from("file_name", fileName);
            }
        };
    }

    @FunctionalInterface
    private interface InputStreamSupplier {
        InputStream open() throws IOException;
    }
}
