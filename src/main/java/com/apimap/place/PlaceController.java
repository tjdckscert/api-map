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
     * 지도 중심 좌표 주변의 음식점/카페를 평점과 함께 반환.
     * 예: /api/places?lat=37.498&lng=127.028&categories=food,cafe
     */
    @GetMapping("/places")
    public Map<String, Object> places(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "food,cafe") List<String> categories) {
        return placeService.findPlaces(lat, lng, categories);
    }
}
