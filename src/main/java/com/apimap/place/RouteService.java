package com.apimap.place;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OSRM 공개 데모 서버로 자동차 경로를 계산한다 (무료, 키 불필요).
 * 데모 서버는 요청량 제한이 있으므로 좌표(약 10m 격자) 기준으로 캐시한다.
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    private static final String OSRM = "https://router.project-osrm.org/route/v1/driving/";
    private static final Duration TTL = Duration.ofMinutes(30);

    private record Cached(Map<String, Object> route, Instant at) {
        boolean fresh() { return at.plus(TTL).isAfter(Instant.now()); }
    }

    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder()
            .defaultHeader("User-Agent", "api-map-personal/0.1 (personal map tool)")
            // OSRM(nginx)의 비표준 deflate 응답이 자동 압축해제와 충돌 → 압축 비활성화
            .defaultHeader("Accept-Encoding", "identity")
            .build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RouteService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @return {distance(m), duration(s), coords: [[lat,lng], ...]} — 실패 시 coords 빈 배열 */
    public Map<String, Object> route(double fromLat, double fromLng, double toLat, double toLng) {
        String key = "%.4f,%.4f>%.4f,%.4f".formatted(fromLat, fromLng, toLat, toLng);
        Cached hit = cache.get(key);
        if (hit != null && hit.fresh()) return hit.route();

        Map<String, Object> route = fetch(fromLat, fromLng, toLat, toLng);
        if (!((List<?>) route.get("coords")).isEmpty()) { // 실패 결과는 캐시하지 않음
            cache.put(key, new Cached(route, Instant.now()));
        }
        return route;
    }

    private Map<String, Object> fetch(double fromLat, double fromLng, double toLat, double toLng) {
        try {
            String uri = OSRM + "%f,%f;%f,%f?overview=full&geometries=geojson&alternatives=false&steps=false"
                    .formatted(fromLng, fromLat, toLng, toLat);
            byte[] raw = http.get().uri(java.net.URI.create(uri)).retrieve().body(byte[].class);
            JsonNode root = mapper.readTree(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            if (!"Ok".equals(root.path("code").asText()) || root.path("routes").isEmpty()) {
                return empty();
            }
            JsonNode r = root.path("routes").get(0);
            List<double[]> coords = new ArrayList<>();
            for (JsonNode c : r.path("geometry").path("coordinates")) {
                coords.add(new double[] { c.get(1).asDouble(), c.get(0).asDouble() }); // [lat, lng]
            }
            return Map.of(
                    "distance", r.path("distance").asDouble(0),
                    "duration", r.path("duration").asDouble(0),
                    "coords", coords);
        } catch (Exception e) {
            log.warn("경로 계산 실패 ({},{} → {},{}): {}", fromLat, fromLng, toLat, toLng, e.getMessage());
            return empty();
        }
    }

    private static Map<String, Object> empty() {
        return Map.of("distance", 0, "duration", 0, "coords", List.of());
    }
}
