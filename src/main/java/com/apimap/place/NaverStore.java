package com.apimap.place;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * collector/naver_match.py 가 채운 data/ratings.json 의 네이버 평점을 읽어
 * 카카오 장소 ID로 조회할 수 있게 보관한다.
 * 네이버는 실시간 호출이 차단(ncaptcha)되므로 오프라인 수집분만 사용한다.
 */
@Component
public class NaverStore {

    private static final Logger log = LoggerFactory.getLogger(NaverStore.class);
    private static final Path DATA_PATH = Path.of("data", "ratings.json");

    private final Map<String, JsonNode> naverByPlaceId = new HashMap<>();

    public NaverStore(ObjectMapper mapper) {
        if (!Files.exists(DATA_PATH)) {
            log.info("네이버 평점 캐시 없음: {}", DATA_PATH.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(DATA_PATH));
            root.properties().forEach(e -> {
                JsonNode naver = e.getValue().get("naver");
                if (naver != null && !naver.isNull()) {
                    naverByPlaceId.put(e.getKey(), naver);
                }
            });
            log.info("네이버 평점 캐시 로드: {}건", naverByPlaceId.size());
        } catch (Exception e) {
            log.warn("네이버 평점 캐시 로드 실패", e);
        }
    }

    public JsonNode get(String placeId) {
        return naverByPlaceId.get(placeId);
    }
}
