package org.example.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentClassifierTest {
    private final IntentClassifier classifier = new IntentClassifier();

    @Test
    void routesExplicitAnalysisToSpatialPath() {
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS, classifier.classify("计算当前红线容积率"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS, classifier.classify("300mm 10h 20years"));
    }

    @Test
    void routesLongCompositeSpatialRequestsToSpatialPath() {
        // Long requests used to score almost identically on both sides and
        // default to chat, so the workflow never started.
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("评估这个地块的容积率、天际线、日照与阴影，以及洪水风险"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("做天际线分析"));
    }

    @Test
    void routesParaphrasedSpatialRequestsToSpatialPath() {
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("算一下这块地的容积率和建筑密度"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("在候选地块里给消防站选址"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("模拟50年一遇暴雨会不会在这里积水"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("分析这栋楼冬天挡不挡光"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("run flood analysis for the current AOI"));
    }

    @Test
    void politeSpatialRequestsAreNotTreatedAsQuestions() {
        // "能不能" softens a request; it is not a factual question.
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("能不能帮我算一下这块地的容积率"));
    }

    @Test
    void keepsCancellationsOutOfSpatialPlanner() {
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("先不用做洪水分析了"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("别算容积率了"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("取消这次选址，先别分析了"));
    }

    @Test
    void scopeNarrowingIsNotACancellation() {
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("不要只算容积率，把天际线也算上"));
    }

    @Test
    void keepsQuestionsOutOfSpatialPlanner() {
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("洪水分析的降水参数是什么"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("你现在的参数是什么"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("为什么 DEM 下载失败"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("天际线分析的结果对吗？"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("这个系统能不能做洪水分析"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("洪水分析需要准备哪些数据"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("帮我写一份选址报告的提纲"));
        assertEquals(IntentClassifier.Intent.GENERAL_CHAT, classifier.classify("讲解一下缓冲区分析的用途"));
    }


    @Test
    void classifiesPlaceBuildingAnalysisAsSpatial() {
        IntentClassifier classifier = new IntentClassifier();
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("对清华大学周边1km做建筑分析"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("以清华大学为中心，生成1km缓冲区"));
        assertEquals(IntentClassifier.Intent.SPATIAL_ANALYSIS,
                classifier.classify("分析天安门附近500米的建筑指标"));
    }
}
