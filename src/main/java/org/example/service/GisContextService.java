package org.example.service;

import org.springframework.stereotype.Service;

@Service
public class GisContextService {
    // 使用简单的缓存存储当前视域的地理数据 (生产环境建议用 Redis)
    private String currentScreenGeoJson;

    public void saveGeoJson(String geojson) {
        this.currentScreenGeoJson = geojson;
    }

    public String getGeoJson() {
        return this.currentScreenGeoJson;
    }
}
