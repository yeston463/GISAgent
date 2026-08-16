package org.example.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.example.spatial.SpatialCapabilityCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Small local binary classifier for routing user input before the GIS planner.
 * The primary signal is an embedding prototype model seeded from labeled
 * examples plus every alias in the spatial capability catalog; a Naive Bayes
 * bag-of-ngrams model trained on the same corpus is the offline fallback.
 * Cancellations and domain questions are vetoed before either model runs so
 * they can never trigger the GIS pipeline.
 */
@Component
public class IntentClassifier {
    public enum Intent { SPATIAL_ANALYSIS, GENERAL_CHAT }

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);
    // Scores blend the top-2 prototype average with the class centroid, which
    // sits below a raw best-match max, so the threshold is slightly lower than
    // a pure nearest-prototype rule would use.
    private static final double SEMANTIC_MIN_SIMILARITY = 0.56;
    private static final double SEMANTIC_MIN_MARGIN = 0.06;
    private static final long SEMANTIC_RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    // DashScope text-embedding-v2 rejects batches larger than 25 texts.
    private static final int EMBED_BATCH_SIZE = 20;

    private static final List<Sample> TRAINING_SET = List.of(
            spatial("计算当前红线的容积率"), spatial("分析 AOI 内建筑密度"),
            spatial("执行洪水分析"), spatial("进行内涝风险筛查"),
            spatial("获取北京市昌平区 DEM"), spatial("采样地形高程"),
            spatial("做天际线分析"), spatial("日照与阴影分析"),
            spatial("为消防站选址"), spatial("查询附近医院距离"),
            spatial("定位上海市浦东新区"), spatial("导入 OSM 建筑数据"),
            spatial("生成 CityEngine 三维成果"), spatial("300mm 10h 20years"),
            spatial("算一下这块地的建筑总面积"), spatial("评估该片区的开发强度"),
            spatial("模拟 50 年一遇暴雨的淹没范围"), spatial("看看下大雨的时候这里会不会积水"),
            spatial("分析这栋楼冬天挡不挡光"), spatial("大寒日的日照时长够不够"),
            spatial("生成沿街建筑高度轮廓"), spatial("在三个候选点里挑一个建养老院"),
            spatial("哪个地块离学校最近"), spatial("统计候选点到消防站的直线距离"),
            spatial("提取当前范围的地形坡度"), spatial("下载这片区域的建筑物轮廓"),
            spatial("把当前 AOI 发布成三维场景"), spatial("圈一块地做用地适宜性评价"),
            spatial("对清华大学周边1公里做建筑分析"), spatial("分析天安门附近500米的建筑指标"),
            spatial("生成以北京大学为中心的2公里缓冲区"), spatial("查看陆家嘴周边建筑高度"),
            spatial("run flood analysis for the current area"), spatial("compute FAR and building density"),
            spatial("skyline profile along the river"), spatial("find the nearest hospital to each candidate site"),
            chat("你现在的参数是什么"), chat("洪水分析的降水参数是什么"),
            chat("系统有哪些功能"), chat("介绍一下这个项目"),
            chat("如何使用这个系统"), chat("你的模型是什么"),
            chat("解释一下容积率的含义"), chat("帮我写一段项目介绍"),
            chat("你好"), chat("今天是什么日期"), chat("为什么 DEM 下载失败"),
            chat("容积率是怎么计算出来的"), chat("洪水分析需要准备哪些数据"),
            chat("日照分析的国家标准是什么"), chat("天际线分析的原理讲一下"),
            chat("选址结果可信吗"), chat("帮我写一份选址报告的提纲"),
            chat("DEM 数据是从哪里来的"), chat("为什么刚才的分析没有出图"),
            chat("这个结果能不能导出"), chat("缓冲区分析一般用在什么场景"),
            chat("谢谢"), chat("你是谁"),
            chat("what is floor area ratio"), chat("how do I draw an AOI")
    );

    private final Map<Intent, Map<String, Integer>> tokenCounts = new HashMap<>();
    private final Map<Intent, Integer> totals = new HashMap<>();
    private final Map<Intent, Integer> documentCounts = new HashMap<>();
    private final EmbeddingModel embeddingModel;
    private final SpatialCapabilityCatalog catalog;
    private volatile SemanticModel semanticModel;
    private volatile long semanticRetryAfterMillis;
    private int vocabularySize;

    public IntentClassifier() {
        this(null, null);
    }

    @Autowired
    public IntentClassifier(EmbeddingModel embeddingModel, SpatialCapabilityCatalog catalog) {
        this.embeddingModel = embeddingModel;
        this.catalog = catalog;
        tokenCounts.put(Intent.SPATIAL_ANALYSIS, new HashMap<>());
        tokenCounts.put(Intent.GENERAL_CHAT, new HashMap<>());
        totals.put(Intent.SPATIAL_ANALYSIS, 0);
        totals.put(Intent.GENERAL_CHAT, 0);
        documentCounts.put(Intent.SPATIAL_ANALYSIS, 0);
        documentCounts.put(Intent.GENERAL_CHAT, 0);
        TRAINING_SET.forEach(sample -> train(sample.intent(), sample.text()));
        // Catalog aliases ("洪水", "flood", "skyline", …) are curated spatial
        // vocabulary, so they double as extra training documents.
        catalogPhrases().forEach(phrase -> train(Intent.SPATIAL_ANALYSIS, phrase));
    }

    public Intent classify(String text) {
        if (text == null || text.isBlank()) return Intent.GENERAL_CHAT;
        // Cancellations ("先不用做洪水分析了") sit next to spatial prototypes in
        // embedding space, so they are vetoed before any model scores them.
        if (hasNegationCue(text)) return Intent.GENERAL_CHAT;
        List<String> tokens = tokens(text);
        double spatial = score(Intent.SPATIAL_ANALYSIS, tokens);
        double chat = score(Intent.GENERAL_CHAT, tokens);
        Intent semanticIntent = classifyBySemanticSimilarity(text);
        if (semanticIntent != null) {
            return semanticIntent;
        }
        // Long composite requests (e.g. "评估这个地块的容积率、天际线、日照与阴影，
        // 以及洪水风险") score almost identically on both sides because most tokens
        // are unseen; the tiny margin then defaults them to chat and the GIS
        // pipeline never runs. Strong domain keywords rescue exactly these
        // requests, while question markers keep chat questions out of the planner.
        if (hasStrongSpatialKeyword(text) && !hasChatQuestionMarker(text)) {
            return Intent.SPATIAL_ANALYSIS;
        }
        // Default to ordinary chat on ties or weak evidence. GIS actions should
        // need positive classifier evidence before they can reach the planner.
        return spatial > chat + 0.35 ? Intent.SPATIAL_ANALYSIS : Intent.GENERAL_CHAT;
    }

    private static final List<String> STRONG_SPATIAL_KEYWORDS = List.of(
            "容积率", "天际线", "日照", "阴影", "洪水", "内涝", "淹没", "选址",
            "红线", "地块", "建筑密度", "缓冲区", "高程", "地形", "遥感", "空间分析",
            "三维", "经纬度", "消防站", "医院", "坡度", "洼地", "径流", "净距", "设施距离",
            "暴雨", "积水", "降雨", "降水", "挡光", "采光", "适宜性", "开发强度",
            "养老", "学校", "建筑", "建筑分析", "建筑指标", "周边", "公里", "千米", "km",
            "缓冲区分析", "地形分析", "容积",
            "slpk", "cityengine", "geoscene", "flood", "aoi", "dem", "gis",
            "skyline", "sunlight", "shadow"
    );

    private static final List<String> CHAT_QUESTION_MARKERS = List.of(
            "是什么", "为什么", "怎么", "如何", "介绍", "解释", "讲解", "含义",
            "参数", "配置", "多少", "哪些", "区别", "帮助", "是否", "原理",
            "讲讲", "说说", "帮我写", "提纲", "模板",
            "能做", "可以做", "支持", "吗", "？", "why", "what", "how", "?"
    );

    private static final List<String> NEGATION_CUES = List.of(
            "不要", "不用", "不需要", "先不", "先别", "别做", "别算", "别执行",
            "别分析", "取消", "停止", "不做"
    );

    // "不要只算容积率，把天际线也算上" narrows scope instead of cancelling.
    private static final List<String> NEGATION_EXEMPTIONS = List.of(
            "不要只", "不只", "不仅", "不止"
    );

    private boolean hasStrongSpatialKeyword(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return STRONG_SPATIAL_KEYWORDS.stream().anyMatch(value::contains);
    }

    private boolean hasChatQuestionMarker(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return CHAT_QUESTION_MARKERS.stream().anyMatch(value::contains);
    }

    private boolean hasNegationCue(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (NEGATION_EXEMPTIONS.stream().anyMatch(value::contains)) return false;
        return NEGATION_CUES.stream().anyMatch(value::contains);
    }

    private List<String> catalogPhrases() {
        if (catalog == null) return List.of();
        List<String> phrases = new ArrayList<>();
        for (SpatialCapabilityCatalog.Capability capability : catalog.capabilities()) {
            phrases.add(capability.id().replace('_', ' '));
            phrases.addAll(capability.aliases());
        }
        return phrases.stream().filter(phrase -> phrase != null && !phrase.isBlank()).distinct().toList();
    }

    /**
     * Qwen's pretrained embedding model compares the input against every
     * prototype of each labeled intent. A class score blends the average of
     * the two nearest prototypes with the class centroid, which keeps one
     * lucky prototype from deciding the route on its own. The local
     * classifier remains the fallback when the embedding service is
     * unavailable or its result is ambiguous.
     */
    private Intent classifyBySemanticSimilarity(String text) {
        if (embeddingModel == null || System.currentTimeMillis() < semanticRetryAfterMillis) {
            return null;
        }
        SemanticModel model = semanticModel();
        if (model == null) return null;
        try {
            List<Float> query = embeddingModel.embed(text).content().vectorAsList();
            double spatial = prototypeScore(query, model, Intent.SPATIAL_ANALYSIS);
            double chat = prototypeScore(query, model, Intent.GENERAL_CHAT);
            double best = Math.max(spatial, chat);
            if (best < SEMANTIC_MIN_SIMILARITY || Math.abs(spatial - chat) < SEMANTIC_MIN_MARGIN) {
                return null;
            }
            Intent decision = spatial > chat ? Intent.SPATIAL_ANALYSIS : Intent.GENERAL_CHAT;
            log.debug("Intent semantic scores spatial={} chat={} -> {}",
                    String.format("%.3f", spatial), String.format("%.3f", chat), decision);
            return decision;
        } catch (Exception e) {
            semanticRetryAfterMillis = System.currentTimeMillis() + SEMANTIC_RETRY_DELAY_MS;
            log.debug("Intent semantic similarity unavailable; using local classifier: {}", e.getMessage());
            return null;
        }
    }

    private SemanticModel semanticModel() {
        SemanticModel cached = semanticModel;
        if (cached != null) return cached;
        synchronized (this) {
            if (semanticModel != null) return semanticModel;
            try {
                List<Sample> corpus = new ArrayList<>(TRAINING_SET);
                catalogPhrases().forEach(phrase -> corpus.add(new Sample(Intent.SPATIAL_ANALYSIS, phrase)));
                List<TextSegment> segments = corpus.stream()
                        .map(sample -> TextSegment.from(sample.text()))
                        .toList();
                List<Embedding> embeddings = embedBatched(segments);
                if (embeddings.size() != corpus.size()) {
                    throw new IllegalStateException("Embedding count does not match intent examples");
                }
                Map<Intent, List<List<Float>>> prototypes = new EnumMap<>(Intent.class);
                for (Intent intent : Intent.values()) {
                    prototypes.put(intent, new ArrayList<>());
                }
                for (int i = 0; i < corpus.size(); i++) {
                    prototypes.get(corpus.get(i).intent()).add(embeddings.get(i).vectorAsList());
                }
                Map<Intent, List<Float>> centroids = new EnumMap<>(Intent.class);
                for (Intent intent : Intent.values()) {
                    centroids.put(intent, centroid(prototypes.get(intent)));
                }
                semanticModel = new SemanticModel(Map.copyOf(prototypes), Map.copyOf(centroids));
                return semanticModel;
            } catch (Exception e) {
                semanticRetryAfterMillis = System.currentTimeMillis() + SEMANTIC_RETRY_DELAY_MS;
                log.debug("Intent semantic prototypes unavailable; using local classifier: {}", e.getMessage());
                return null;
            }
        }
    }

    private List<Embedding> embedBatched(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (int start = 0; start < segments.size(); start += EMBED_BATCH_SIZE) {
            List<TextSegment> batch = segments.subList(start, Math.min(start + EMBED_BATCH_SIZE, segments.size()));
            embeddings.addAll(embeddingModel.embedAll(batch).content());
        }
        return embeddings;
    }

    private double prototypeScore(List<Float> query, SemanticModel model, Intent intent) {
        List<List<Float>> candidates = model.prototypes().get(intent);
        if (candidates == null || candidates.isEmpty()) return -1d;
        double best = -1d;
        double second = -1d;
        for (List<Float> candidate : candidates) {
            double similarity = cosineSimilarity(query, candidate);
            if (similarity > best) {
                second = best;
                best = similarity;
            } else if (similarity > second) {
                second = similarity;
            }
        }
        double top = second >= 0d ? (best + second) / 2d : best;
        double centroidSimilarity = cosineSimilarity(query, model.centroids().get(intent));
        return centroidSimilarity < 0d ? top : 0.75d * top + 0.25d * centroidSimilarity;
    }

    private static List<Float> centroid(List<List<Float>> vectors) {
        if (vectors == null || vectors.isEmpty()) return List.of();
        int dimensions = vectors.get(0).size();
        double[] sum = new double[dimensions];
        int count = 0;
        for (List<Float> vector : vectors) {
            if (vector.size() != dimensions) continue;
            count++;
            for (int i = 0; i < dimensions; i++) sum[i] += vector.get(i);
        }
        if (count == 0) return List.of();
        List<Float> centroid = new ArrayList<>(dimensions);
        for (double value : sum) centroid.add((float) (value / count));
        return centroid;
    }

    private double cosineSimilarity(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) return -1d;
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        return leftNorm == 0d || rightNorm == 0d ? -1d : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private void train(Intent intent, String text) {
        documentCounts.merge(intent, 1, Integer::sum);
        for (String token : tokens(text)) {
            tokenCounts.get(intent).merge(token, 1, Integer::sum);
            totals.merge(intent, 1, Integer::sum);
        }
        vocabularySize = Math.max(vocabularySize, tokenCounts.get(Intent.SPATIAL_ANALYSIS).size()
                + tokenCounts.get(Intent.GENERAL_CHAT).size());
    }

    private double score(Intent intent, List<String> tokens) {
        int documents = documentCounts.values().stream().mapToInt(Integer::intValue).sum();
        double score = Math.log((documentCounts.get(intent) + 1d) / (documents + 2d));
        int denominator = totals.get(intent) + Math.max(1, vocabularySize);
        for (String token : tokens) {
            int count = tokenCounts.get(intent).getOrDefault(token, 0);
            score += Math.log((count + 1d) / denominator);
        }
        return score;
    }

    private List<String> tokens(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        ArrayList<String> tokens = new ArrayList<>();
        String compact = normalized.replace(" ", "");
        for (int i = 0; i < compact.length(); i++) {
            tokens.add(String.valueOf(compact.charAt(i)));
            if (i + 1 < compact.length()) tokens.add(compact.substring(i, i + 2));
        }
        for (String word : normalized.split("[^a-z0-9.]+")) {
            if (!word.isBlank()) tokens.add("word:" + word);
        }
        return tokens;
    }

    private record SemanticModel(Map<Intent, List<List<Float>>> prototypes, Map<Intent, List<Float>> centroids) { }

    private static Sample spatial(String text) { return new Sample(Intent.SPATIAL_ANALYSIS, text); }
    private static Sample chat(String text) { return new Sample(Intent.GENERAL_CHAT, text); }
    private record Sample(Intent intent, String text) { }
}
