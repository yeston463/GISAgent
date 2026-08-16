package org.example.agent;

import org.example.spatial.SpatialCapabilityCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioGenerationTest {

    private SpatialCapabilityCatalog catalog;
    private List<Map<String, Object>> sampleBuildings;
    private Map<String, Object> sampleAoi;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new SpatialCapabilityCatalog();
        var loadMethod = SpatialCapabilityCatalog.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(catalog);
        sampleBuildings = new ArrayList<>();
        sampleBuildings.add(createBuilding(30.0, 5, 200.0));
        sampleBuildings.add(createBuilding(18.0, 4, 150.0));
        sampleBuildings.add(createBuilding(45.0, 12, 300.0));
        sampleAoi = Map.of("siteArea", 5000.0);
    }

    private Map<String, Object> createBuilding(double height, double floors, double footprint) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("height", height);
        b.put("floors", floors);
        b.put("footprint", footprint);
        return b;
    }

    @Test
    void generateScenariosProducesThreeScenarios() {
        AgentLoopService service = createServiceWithCatalog();
        List<Scenario> scenarios = service.generateScenarios(sampleAoi, sampleBuildings, catalog);
        assertEquals(3, scenarios.size());
        assertEquals("conservative", scenarios.get(0).id());
        assertEquals("balanced", scenarios.get(1).id());
        assertEquals("aggressive", scenarios.get(2).id());
    }

    @Test
    void conservativeStrategyKeepsBuildingsUnchanged() {
        ConservativeStrategy strategy = new ConservativeStrategy();
        List<Map<String, Object>> result = strategy.apply(sampleBuildings, Map.of());
        assertEquals(3, result.size());
        assertEquals(30.0, result.get(0).get("height"));
        assertEquals(45.0, result.get(2).get("height"));
    }

    @Test
    void balancedStrategyCapsHeightAndAdjustsFootprint() {
        BalancedStrategy strategy = new BalancedStrategy();
        Map<String, Double> context = Map.of("heightLimit", 24.0, "densityFactor", 0.85);
        List<Map<String, Object>> result = strategy.apply(sampleBuildings, context);
        assertEquals(3, result.size());
        assertEquals(24.0, result.get(0).get("height"));
        assertEquals(18.0, result.get(1).get("height"));
        assertEquals(24.0, result.get(2).get("height"));
        assertEquals(200.0 * 0.85, result.get(0).get("footprint"));
    }

    @Test
    void aggressiveStrategyRebuildsToTargetFar() {
        AggressiveStrategy strategy = new AggressiveStrategy();
        Map<String, Double> context = Map.of("targetFar", 2.0, "heightLimit", 60.0, "siteArea", 5000.0);
        List<Map<String, Object>> result = strategy.apply(sampleBuildings, context);
        assertFalse(result.isEmpty());
        for (Map<String, Object> b : result) {
            assertTrue((double) b.get("height") <= 60.0);
            assertNotNull(b.get("floors"));
            assertNotNull(b.get("footprint"));
        }
    }

    @Test
    void evaluatorProducesResultsForEachScenario() {
        AgentLoopService service = createServiceWithCatalog();
        List<Scenario> scenarios = service.generateScenarios(sampleAoi, sampleBuildings, catalog);
        double originalFar = 1.5;
        List<ScenarioResult> results = service.evaluateScenarios(scenarios, "urban_metrics", originalFar);
        assertEquals(3, results.size());
        for (ScenarioResult result : results) {
            assertNotNull(result.scenario());
            assertNotNull(result.metrics());
            assertNotNull(result.violations());
            assertTrue(result.score() >= 0.0 && result.score() <= 1.0);
        }
    }

    @Test
    void scoringProducesCorrectRanking() {
        AgentLoopService service = createServiceWithCatalog();
        List<Scenario> scenarios = service.generateScenarios(sampleAoi, sampleBuildings, catalog);
        List<ScenarioResult> results = service.evaluateScenarios(scenarios, "urban_metrics", 1.5);
        double maxScore = results.stream().mapToDouble(ScenarioResult::score).max().orElse(0);
        long bestCount = results.stream().filter(r -> r.score() == maxScore).count();
        assertTrue(bestCount >= 1);
    }

    @Test
    void scenarioRecordIsImmutable() {
        List<Map<String, Object>> buildings = new ArrayList<>(sampleBuildings);
        Map<String, Double> params = new LinkedHashMap<>(Map.of("key", 1.0));
        Scenario scenario = new Scenario("test", "Test", "desc", params, buildings);
        assertEquals("test", scenario.id());
        assertEquals("Test", scenario.name());
        assertEquals(3, scenario.buildings().size());
    }

    @Test
    void scenarioEvaluatorCalculatesMetrics() {
        ScenarioEvaluator evaluator = new ScenarioEvaluator(catalog);
        Scenario scenario = new Scenario("test", "Test", "desc",
                Map.of("siteArea", 5000.0, "heightLimit", 24.0, "targetFar", 2.0),
                sampleBuildings);
        Map<String, Object> metrics = evaluator.calculateMetrics(scenario);
        assertTrue(metrics.containsKey("far"));
        assertTrue(metrics.containsKey("building_density"));
        assertTrue(metrics.containsKey("buildingHeight"));
        assertEquals(3, metrics.get("building_count"));
    }

    @Test
    void complianceScoreDecreasesWithViolations() {
        ScenarioEvaluator evaluator = new ScenarioEvaluator(catalog);
        Scenario scenario = new Scenario("test", "Test", "desc",
                Map.of("siteArea", 5000.0, "targetFar", 2.0), sampleBuildings);
        Map<String, Object> metrics = evaluator.calculateMetrics(scenario);
        List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics("urban_metrics", metrics);
        double score = evaluator.scoreScenario(violations, metrics, 1.5, scenario);
        assertTrue(score >= 0.0 && score <= 1.0);
        if (!violations.isEmpty()) {
            double noViolationScore = evaluator.scoreScenario(List.of(), metrics, 1.5, scenario);
            assertTrue(noViolationScore > score);
        }
    }

    private AgentLoopService createServiceWithCatalog() {
        AgentLoopService service = new AgentLoopService();
        try {
            var field = AgentLoopService.class.getDeclaredField("spatialCapabilityCatalog");
            field.setAccessible(true);
            field.set(service, catalog);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject catalog", e);
        }
        return service;
    }

    @Test
    void appendPlanningComparisonCommandBuildsBeforeAfterPairs() throws Exception {
        AgentLoopService service = createServiceWithCatalog();
        var method = AgentLoopService.class.getDeclaredMethod(
                "appendPlanningComparisonCommand",
                List.class, Map.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("far", 0.354);
        before.put("building_density", 12.5);
        before.put("height_stats", Map.of("max", 60.0));
        before.put("green_rate", 5.0);

        Map<String, Object> planned = new LinkedHashMap<>();
        planned.put("far", 1.234);
        planned.put("building_density", 25.0);
        planned.put("buildingHeight", 54.0);
        planned.put("green_rate", 25.0);
        Map<String, Object> planningResult = new LinkedHashMap<>();
        planningResult.put("planned", Map.of("metrics", planned));

        List<Map<String, Object>> commands = new ArrayList<>();
        method.invoke(service, commands, before, planningResult);

        assertEquals(1, commands.size());
        Map<String, Object> command = commands.get(0);
        assertEquals("comparePlanningScenarios", command.get("action"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) command.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> comparison = (Map<String, Object>) params.get("comparison");
        @SuppressWarnings("unchecked")
        Map<String, Object> far = (Map<String, Object>) comparison.get("far");
        assertEquals(0.35, far.get("before"));
        assertEquals(1.23, far.get("after"));
        @SuppressWarnings("unchecked")
        Map<String, Object> height = (Map<String, Object>) comparison.get("buildingHeight");
        assertEquals(60.0, height.get("before"));
        assertEquals(54.0, height.get("after"));
    }

    @Test
    void appendPlanningComparisonCommandSkipsWhenNoPlannedMetrics() throws Exception {
        AgentLoopService service = createServiceWithCatalog();
        var method = AgentLoopService.class.getDeclaredMethod(
                "appendPlanningComparisonCommand",
                List.class, Map.class, Map.class);
        method.setAccessible(true);

        List<Map<String, Object>> commands = new ArrayList<>();
        method.invoke(service, commands, Map.of("far", 0.3), Map.of("current", Map.of("metrics", Map.of("far", 0.3))));
        assertTrue(commands.isEmpty());
    }
}

