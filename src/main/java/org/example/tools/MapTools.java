package org.example.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.Tool;
import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DecimalFormat;

@Component
public class MapTools {
	private static final String API_KEY = "045a2b80aaaa4ddecdd0f23acf7803f1";
	private static final String BASE_URL = "https://restapi.amap.com/v3/geocode/geo";

	@Tool("获取地点的经纬度坐标")
	public String geocode(String address) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newHttpClient();
		String url = String.format("%s?key=%s&address=%s", BASE_URL, API_KEY, address);
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root = mapper.readTree(response.body());
		if ("1".equals(root.get("status").asText())) {
			JsonNode geocode = root.get("geocodes").get(0);
			String location = geocode.get("location").asText(); // 高德返回的是 GCJ-02

			String[] parts = location.split(",");
			double lng = Double.parseDouble(parts[0]);
			double lat = Double.parseDouble(parts[1]);

			// 【关键修复】将高德坐标转为 WGS-84
			double[] wgsCoord = CoordinateTransform.gcj02ToWgs84(lng, lat);

			String correctedLocation = String.format("%.6f,%.6f", wgsCoord[0], wgsCoord[1]);
			System.out.println("高德原始坐标: " + location + " -> 纠偏后 WGS84: " + correctedLocation);

			return correctedLocation;
		}  else {
			System.out.println("查询失败：" + root.get("info").asText());
			return "116.4074, 39.9042"; // 默认北京
		}


	}

//	 示例 2: 模拟缓冲区分析
	@Tool("对给定坐标点进行缓冲区分析，生成 GeoJSON 面数据")
	public String bufferAnalysis(double lng, double lat, double radiusMeter) {
		// 调用算法库(如 JTS 或调用外部 Python 脚本)
		// 返回符合 RAG 规范的 GeoJSON 字符串
		try {
			// 1. 创建JTS几何工厂（WGS84坐标系，SRID:4326）
			GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

			// 2. 创建坐标点
			Coordinate coord = new Coordinate(lng, lat);
			Point point = geometryFactory.createPoint(coord);

			// 3. 计算缓冲区（注意：JTS的buffer单位是度，需要将米转换为度）
			double radiusDegrees = radiusMeter / 111000.0;
			Geometry buffer = point.buffer(radiusDegrees, 12); // 32表示多边形边数，越高越平滑

			// 4. 确保结果是多边形（如果不是，转换为多边形）
			Polygon bufferPolygon;
			if (buffer instanceof Polygon) {
				bufferPolygon = (Polygon) buffer;
			} else {
				// 如果不是多边形，取凸包
				bufferPolygon = (Polygon) buffer.convexHull();
			}

			// 5. 生成GeoJSON
			ObjectMapper mapper = new ObjectMapper();
			ObjectNode featureCollection = mapper.createObjectNode();
			featureCollection.put("type", "FeatureCollection");

			ArrayNode features = mapper.createArrayNode();
			ObjectNode feature = mapper.createObjectNode();
			feature.put("type", "Feature");

			// 几何体
			ObjectNode geometry = mapper.createObjectNode();
			geometry.put("type", "Polygon");

			ArrayNode coordinatesArray = mapper.createArrayNode();
			Coordinate[] coords = bufferPolygon.getCoordinates();

			// 外环坐标
			ArrayNode outerRing = mapper.createArrayNode();
			for (Coordinate c : coords) {
				ArrayNode coordPair = mapper.createArrayNode();
				// 格式化坐标，保留6位小数
				DecimalFormat df = new DecimalFormat("#.######");
				coordPair.add(Double.parseDouble(df.format(c.x)));
				coordPair.add(Double.parseDouble(df.format(c.y)));
				outerRing.add(coordPair);
			}
			// 闭合多边形（确保首尾坐标相同）
			if (coords.length > 0 && !coords[0].equals(coords[coords.length - 1])) {
				ArrayNode coordPair = mapper.createArrayNode();
				coordPair.add(coords[0].x);
				coordPair.add(coords[0].y);
				outerRing.add(coordPair);
			}

			coordinatesArray.add(outerRing);
			geometry.set("coordinates", coordinatesArray);

			// 属性
			ObjectNode properties = mapper.createObjectNode();
			properties.put("center_lng", lng);
			properties.put("center_lat", lat);
			properties.put("radius_m", radiusMeter);
			properties.put("description",
				String.format("以(%.6f, %.6f)为中心，%d米半径的缓冲区", lng, lat, (int)radiusMeter));

			feature.set("geometry", geometry);
			feature.set("properties", properties);
			features.add(feature);

			featureCollection.set("features", features);

			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(featureCollection);

		} catch (Exception e) {
			e.printStackTrace();
			return String.format("""
            {
                "type": "FeatureCollection",
                "features": [{
                    "type": "Feature",
                    "properties": {
                        "error": "%s",
                        "center_lng": %f,
                        "center_lat": %f,
                        "radius_m": %f
                    },
                    "geometry": null
                }]
            }""", e.getMessage(), lng, lat, radiusMeter);
		}

	}


}
