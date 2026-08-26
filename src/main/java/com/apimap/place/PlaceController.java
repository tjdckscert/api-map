package com.apimap.place;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    /**
     * 지도 뷰포트(bbox) 안의 음식점/카페를 평점과 함께 반환.
     * 예: /api/places?swLat=37.49&swLng=127.02&neLat=37.51&neLng=127.04&categories=food,cafe
     */
    @GetMapping("/places")
    public Map<String, Object> places(
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam double neLat,
            @RequestParam double neLng,
            @RequestParam(defaultValue = "food,cafe") List<String> categories) {
        return placeService.findPlaces(swLat, swLng, neLat, neLng, categories);
    }

    /** 검색창: 음식점/카페 자유 검색. 뷰포트를 주면 그 주변을 우선한다. */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String q,
            @RequestParam(required = false) Double swLat,
            @RequestParam(required = false) Double swLng,
            @RequestParam(required = false) Double neLat,
            @RequestParam(required = false) Double neLng) {
        return Map.of("places", placeService.searchByQuery(q, swLat, swLng, neLat, neLng));
    }
}
