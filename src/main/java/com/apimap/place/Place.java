package com.apimap.place;

import com.fasterxml.jackson.databind.JsonNode;

/** 지도에 표시할 장소 한 곳. kakao/naver 평점은 없으면 null. */
public record Place(
        String id,
        String name,
        String category,
        double lat,
        double lng,
        String address,
        String placeUrl,
        KakaoRating kakao,
        JsonNode naver
) {
    public record KakaoRating(double rating, int count, int reviews) {}
}
