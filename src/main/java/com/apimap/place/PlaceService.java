package com.apimap.place;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 장소 검색 파이프라인:
 *  1. 지도 뷰포트(위경도 bbox)를 WCONGNAMUL rect로 변환해
 *     카카오맵 내부 검색(mcheck=Y: 현 지도 내 검색)으로 평점 포함 장소를 실시간 조회
 *  2. 네이버 평점은 오프라인 수집분(NaverStore)에서 병합
 *  3. 상태바 표시용 지역명은 Nominatim(OSM) 역지오코딩 (키 불필요)
 * 상위 서비스 부하를 줄이기 위해 지역명/검색 결과 모두 메모리 캐시한다.
 */
@Service
public class PlaceService {

    private static final Logger log = LoggerFactory.getLogger(PlaceService.class);

    private static final String KAKAO_SEARCH = "https://search.map.kakao.com/mapsearch/map.daum";
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/reverse";
    private static final String USER_AGENT = "api-map-personal/0.1 (personal map tool)";
    private static final Map<String, String> CATEGORY_KEYWORDS = Map.of(
            "food", "음식점",
            "cafe", "카페"
    );
    private static final int PAGES_PER_CATEGORY = 3; // 페이지당 15건 → 카테고리당 최대 45건
    private static final Duration REGION_TTL = Duration.ofHours(24);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(10);

    private final RestClient http = RestClient.builder()
            .defaultHeader("User-Agent", USER_AGENT)
            .build();
    private final ObjectMapper mapper;
    private final NaverStore naverStore;

    private record Cached<T>(T value, Instant at) {
        boolean fresh(Duration ttl) { return at.plus(ttl).isAfter(Instant.now()); }
    }

    private final Map<String, Cached<String>> regionCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<Place>>> searchCache = new ConcurrentHashMap<>();

    public PlaceService(ObjectMapper mapper, NaverStore naverStore) {
        this.mapper = mapper;
        this.naverStore = naverStore;
    }

    /** 뷰포트(bbox) 안의 음식점/카페를 평점과 함께 반환. */
    public Map<String, Object> findPlaces(double swLat, double swLng, double neLat, double neLng,
                                          List<String> categories) {
        double[] sw = Wcong.fromWgs84(swLat, swLng);
        double[] ne = Wcong.fromWgs84(neLat, neLng);
        String rect = "%.0f,%.0f,%.0f,%.0f".formatted(sw[0], sw[1], ne[0], ne[1]);

        Map<String, Place> merged = new LinkedHashMap<>();
        for (String category : categories) {
            String keyword = CATEGORY_KEYWORDS.get(category);
            if (keyword == null) continue;
            for (Place p : searchCached(keyword, rect)) {
                merged.putIfAbsent(p.id(), p);
            }
        }
        String region = resolveRegion((swLat + neLat) / 2, (swLng + neLng) / 2);
        return Map.of("region", region, "places", List.copyOf(merged.values()));
    }

    private static final int SEARCH_MAX_RESULTS = 15;
    private static final int SEARCH_MAX_PAGES = 4;

    /**
     * 검색창용: 자유 검색어로 음식점/카페만 검색.
     * 뷰포트가 주어지면 그 주변(확장 영역)을 먼저 찾고, 부족하면 전국 검색으로 보충한다.
     */
    public List<Place> searchByQuery(String query, Double swLat, Double swLng, Double neLat, Double neLng) {
        String rect = null;
        if (swLat != null && swLng != null && neLat != null && neLng != null) {
            double[] sw = Wcong.fromWgs84(swLat, swLng);
            double[] ne = Wcong.fromWgs84(neLat, neLng);
            rect = "%.0f,%.0f,%.0f,%.0f".formatted(sw[0], sw[1], ne[0], ne[1]);
        }
        String key = "q|" + query + "|" + (rect == null ? "all" : rect);
        Cached<List<Place>> hit = searchCache.get(key);
        if (hit != null && hit.fresh(SEARCH_TTL)) return hit.value();

        List<Place> places = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (rect != null) collectFoodPlaces(query, rect, places, seen);
        if (places.size() < SEARCH_MAX_RESULTS) collectFoodPlaces(query, null, places, seen);

        searchCache.put(key, new Cached<>(places, Instant.now()));
        return places;
    }

    /** 음식점/카페(cate_name_depth1=음식점)만 걸러 최대 SEARCH_MAX_RESULTS건까지 수집. */
    private void collectFoodPlaces(String query, String rect, List<Place> out, java.util.Set<String> seen) {
        for (int page = 1; page <= SEARCH_MAX_PAGES && out.size() < SEARCH_MAX_RESULTS; page++) {
            try {
                UriComponentsBuilder b = UriComponentsBuilder.fromUriString(KAKAO_SEARCH)
                        .queryParam("q", query)
                        .queryParam("msFlag", "A")
                        .queryParam("sort", "0")
                        .queryParam("page", page);
                if (rect != null) b.queryParam("mcheck", "Y").queryParam("rect", rect);
                String body = http.get().uri(b.build().toUriString())
                        .header("Referer", "https://map.kakao.com/")
                        .retrieve().body(String.class);
                JsonNode placeList = mapper.readTree(body).path("place");
                if (!placeList.isArray() || placeList.isEmpty()) break;
                for (JsonNode p : placeList) {
                    if (out.size() >= SEARCH_MAX_RESULTS) break;
                    if (!"음식점".equals(p.path("cate_name_depth1").asText(""))) continue;
                    Place place = toPlace(p, "");
                    if (place != null && seen.add(place.id())) out.add(place);
                }
            } catch (Exception e) {
                log.warn("검색 실패 (q={}, rect={}, page={}): {}", query, rect, page, e.getMessage());
                break;
            }
        }
    }

    private List<Place> searchCached(String keyword, String rect) {
        String key = keyword + "|" + rect;
        Cached<List<Place>> hit = searchCache.get(key);
        if (hit != null && hit.fresh(SEARCH_TTL)) return hit.value();

        List<Place> places = searchKakao(keyword, rect);
        searchCache.put(key, new Cached<>(places, Instant.now()));
        return places;
    }

    /** 카카오맵 내부 검색: mcheck=Y + rect(WCONGNAMUL)로 '현 지도 내 검색'과 동일하게 조회. */
    private List<Place> searchKakao(String keyword, String rect) {
        List<Place> result = new ArrayList<>();
        for (int page = 1; page <= PAGES_PER_CATEGORY; page++) {
            try {
                String uri = UriComponentsBuilder.fromUriString(KAKAO_SEARCH)
                        .queryParam("q", keyword)
                        .queryParam("msFlag", "A")
                        .queryParam("mcheck", "Y")
                        .queryParam("rect", rect)
                        .queryParam("sort", "0")
                        .queryParam("page", page)
                        .build().toUriString();
                String body = http.get().uri(uri)
                        .header("Referer", "https://map.kakao.com/")
                        .retrieve().body(String.class);
                JsonNode placeList = mapper.readTree(body).path("place");
                if (!placeList.isArray() || placeList.isEmpty()) break;
                for (JsonNode p : placeList) {
                    Place place = toPlace(p, keyword);
                    if (place != null) result.add(place);
                }
            } catch (Exception e) {
                log.warn("카카오 검색 실패 (q={}, rect={}, page={}): {}", keyword, rect, page, e.getMessage());
                break;
            }
        }
        return result;
    }

    private Place toPlace(JsonNode p, String categoryLabel) {
        String id = p.path("confirmid").asText("");
        if (id.isEmpty()) return null;

        Place.KakaoRating kakao = null;
        int ratingCount = p.path("rating_count").asInt(0);
        if (ratingCount > 0) {
            kakao = new Place.KakaoRating(
                    p.path("rating_average").asDouble(0),
                    ratingCount,
                    p.path("reviewCount").asInt(0));
        }
        String category = p.path("cate_name_depth3").asText("");
        if (category.isEmpty()) category = p.path("cate_name_depth2").asText("");
        if (category.isEmpty()) category = categoryLabel;

        return new Place(
                id,
                p.path("name").asText(""),
                category,
                p.path("lat").asDouble(0),
                p.path("lon").asDouble(0),
                p.path("new_address").asText(p.path("address").asText("")),
                "https://place.map.kakao.com/" + id,
                kakao,
                naverStore.get(id));
    }

    /** 상태바 표시용 지역명. 좌표(약 100m 격자 단위) 기준 캐시. */
    private String resolveRegion(double lat, double lng) {
        String key = String.format("%.3f,%.3f", lat, lng);
        Cached<String> hit = regionCache.get(key);
        if (hit != null && hit.fresh(REGION_TTL)) return hit.value();

        String region = fetchRegion(lat, lng);
        regionCache.put(key, new Cached<>(region, Instant.now()));
        return region;
    }

    private String fetchRegion(double lat, double lng) {
        try {
            String uri = UriComponentsBuilder.fromUriString(NOMINATIM)
                    .queryParam("format", "jsonv2")
                    .queryParam("lat", lat)
                    .queryParam("lon", lng)
                    .queryParam("zoom", 16)
                    .queryParam("accept-language", "ko")
                    .build().toUriString();
            JsonNode address = mapper.readTree(http.get().uri(uri).retrieve().body(String.class))
                    .path("address");
            List<String> parts = new ArrayList<>();
            for (String field : List.of("city", "county", "borough", "city_district",
                    "suburb", "quarter", "neighbourhood", "town", "village")) {
                String v = address.path(field).asText("");
                if (!v.isEmpty() && !parts.contains(v)) parts.add(v);
            }
            if (!parts.isEmpty()) return String.join(" ", parts);
        } catch (Exception e) {
            log.warn("역지오코딩 실패 ({}, {}): {}", lat, lng, e.getMessage());
        }
        return "";
    }
}
