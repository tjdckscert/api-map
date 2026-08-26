package com.apimap.place;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 네이버 블로그 검색 API(공식 오픈 API)로 장소의 블로그 리뷰 글을 가져온다.
 * 키 발급: developers.naver.com → 애플리케이션 등록 → '검색' API 선택.
 * 키는 프로젝트 루트의 naver-keys.yml(gitignore 대상) 또는 환경변수로 설정한다.
 */
@Service
public class NaverBlogService {

    private static final Logger log = LoggerFactory.getLogger(NaverBlogService.class);
    private static final String BLOG_API = "https://openapi.naver.com/v1/search/blog.json";
    private static final Duration TTL = Duration.ofHours(6);

    public record BlogPost(String title, String link, String description, String blogger, String date) {}

    private record Cached(List<BlogPost> posts, Instant at) {
        boolean fresh() { return at.plus(TTL).isAfter(Instant.now()); }
    }

    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.create();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public NaverBlogService(ObjectMapper mapper,
                            @Value("${naver.client-id:}") String clientId,
                            @Value("${naver.client-secret:}") String clientSecret) {
        this.mapper = mapper;
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        log.info("네이버 블로그 검색 API: {}", configured() ? "사용" : "키 미설정 (naver-keys.yml 참고)");
    }

    public boolean configured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** "{지역} {상호명}"으로 블로그 글 검색. 키 미설정/실패 시 빈 목록. */
    public Map<String, Object> search(String name, String area) {
        if (!configured()) return Map.of("configured", false, "posts", List.of());

        String query = (area.isBlank() ? "" : area + " ") + name;
        Cached hit = cache.get(query);
        List<BlogPost> posts = (hit != null && hit.fresh()) ? hit.posts() : fetch(query);
        cache.put(query, new Cached(posts, Instant.now()));
        return Map.of("configured", true, "posts", posts);
    }

    private List<BlogPost> fetch(String query) {
        List<BlogPost> posts = new ArrayList<>();
        try {
            String uri = UriComponentsBuilder.fromUriString(BLOG_API)
                    .queryParam("query", query)
                    .queryParam("display", 5)
                    .queryParam("sort", "sim")
                    .build().toUriString();
            JsonNode items = mapper.readTree(http.get().uri(uri)
                    .header("X-Naver-Client-Id", clientId)
                    .header("X-Naver-Client-Secret", clientSecret)
                    .retrieve().body(String.class)).path("items");
            for (JsonNode item : items) {
                posts.add(new BlogPost(
                        clean(item.path("title").asText("")),
                        item.path("link").asText(""),
                        clean(item.path("description").asText("")),
                        clean(item.path("bloggername").asText("")),
                        formatDate(item.path("postdate").asText(""))));
            }
        } catch (Exception e) {
            log.warn("블로그 검색 실패 (q={}): {}", query, e.getMessage());
        }
        return posts;
    }

    /** 검색 API가 붙이는 <b> 태그와 HTML 엔티티 제거. */
    private static String clean(String s) {
        return s.replaceAll("<[^>]*>", "")
                .replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'");
    }

    private static String formatDate(String yyyymmdd) {
        if (yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "." + yyyymmdd.substring(4, 6) + "." + yyyymmdd.substring(6);
    }
}
