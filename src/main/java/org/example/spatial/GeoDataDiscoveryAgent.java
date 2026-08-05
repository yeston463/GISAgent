package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.example.service.GisContextService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Presents candidate datasets. Import remains an explicit user action. */
@Service
public class GeoDataDiscoveryAgent {
    private final SpatialDataDiscoveryService guidance;
    private final AtlasGisMcpConnector atlas;
    private final GisContextService context;
    private final OsmOverpassConnector osm;
    private final ArcGisPortalConnector arcgis;

    public GeoDataDiscoveryAgent(SpatialDataDiscoveryService guidance, AtlasGisMcpConnector atlas, GisContextService context, OsmOverpassConnector osm, ArcGisPortalConnector arcgis) {
        this.guidance = guidance; this.atlas = atlas; this.context = context; this.osm = osm; this.arcgis = arcgis;
    }

    public Map<String, Object> discover(List<String> datasets) {
        List<String> requested = datasets == null || datasets.isEmpty() ? List.of("dem", "buildings", "rainfall_scenario") : datasets;
        JSONObject state; try { state = JSON.parseObject(context.getGeoJson()); } catch (Exception ignored) { state = new JSONObject(); }
        Object aoi = state.get("aoi"); List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> item : guidance.recommend(requested)) {
            candidates.add(item);
            String dataset = String.valueOf(item.get("dataset"));
            candidates.addAll(atlas.search(dataset, aoi instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of()));
            candidates.addAll(arcgis.search(dataset));
            if (aoi instanceof JSONObject object && object.getJSONObject("geometry") != null) {
                List<?> bbox = bounds(object.getJSONObject("geometry"));
                if (bbox != null && List.of("buildings", "roads", "waterways").contains(dataset)) candidates.add(osm.discover(dataset, Map.of("bbox", bbox)));
            }
        }
        return Map.of("mode", "read_only_discovery", "atlasMcpConfigured", atlas.configured(), "candidates", candidates,
                "requiresUserConfirmation", true);
    }

    public JSONObject importOsm(String dataset) {
        JSONObject state = JSON.parseObject(context.getGeoJson()); JSONObject aoi = state.getJSONObject("aoi");
        if (aoi == null || aoi.getJSONObject("geometry") == null) throw new IllegalArgumentException("aoi_required_for_osm_import");
        List<?> bbox = bounds(aoi.getJSONObject("geometry")); if (bbox == null) throw new IllegalArgumentException("aoi_geometry_invalid");
        return osm.fetchGeoJson(dataset, Map.of("bbox", bbox));
    }

    private List<Double> bounds(JSONObject geometry) {
        java.util.List<Double> values = new java.util.ArrayList<>(); collect(geometry.get("coordinates"), values);
        if (values.size() < 4) return null; double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
        for(int i=0;i+1<values.size();i+=2){double x=values.get(i),y=values.get(i+1);minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);} return List.of(minX,minY,maxX,maxY);
    }
    private void collect(Object node, List<Double> values) { if(node instanceof com.alibaba.fastjson.JSONArray a){ if(a.size()>=2&&a.get(0) instanceof Number&&a.get(1) instanceof Number){values.add(a.getDoubleValue(0));values.add(a.getDoubleValue(1));}else for(Object item:a)collect(item,values);} }
}
