/**
 * API Map — 음식점·카페 평점 지도
 *
 * 구조:
 *  - 장소 마커: 카카오맵 JS SDK 장소 검색(공식)으로 현재 지도 영역을 실시간 검색
 *  - 평점: 사전 수집된 data/ratings.json 캐시에서 장소 ID로 매칭
 *    (브라우저에서 평점 원본을 직접 호출할 수 없음 — CORS 차단)
 */
(function () {
  'use strict';

  var CATEGORIES = [
    { code: 'FD6', key: 'food', color: '#e8590c' },
    { code: 'CE7', key: 'cafe', color: '#5f3dc4' },
  ];
  var MAX_PAGES = 3; // 카테고리당 15개 × 3페이지 = 45개 (SDK 최대)

  var map, places, clusterer;
  var ratings = {}; // place id → { kakao: {...}, naver: {...} }
  var markers = []; // 현재 표시 중인 마커/오버레이
  var openCard = null;
  var searchSeq = 0;

  if (!CONFIG.KAKAO_JS_KEY) {
    document.getElementById('setup-notice').hidden = false;
    return;
  }

  // 카카오맵 SDK 동적 로드
  var script = document.createElement('script');
  script.src = 'https://dapi.kakao.com/v2/maps/sdk.js?appkey=' + CONFIG.KAKAO_JS_KEY +
    '&libraries=services,clusterer&autoload=false';
  script.onload = function () { kakao.maps.load(init); };
  document.head.appendChild(script);

  function init() {
    var saved = loadViewport();
    map = new kakao.maps.Map(document.getElementById('map'), {
      center: new kakao.maps.LatLng(saved.lat, saved.lng),
      level: saved.level,
    });
    places = new kakao.maps.services.Places(map);
    clusterer = new kakao.maps.MarkerClusterer({
      map: map,
      averageCenter: true,
      minLevel: 6,
      disableClickZoom: false,
    });

    loadRatings().then(function () {
      var timer = null;
      kakao.maps.event.addListener(map, 'idle', function () {
        saveViewport();
        clearTimeout(timer);
        timer = setTimeout(searchViewport, 400);
      });
      document.querySelectorAll('#controls input, #controls select').forEach(function (el) {
        el.addEventListener('change', searchViewport);
      });
      searchViewport();
    });
  }

  function loadRatings() {
    return fetch('data/ratings.json')
      .then(function (r) { return r.ok ? r.json() : {}; })
      .then(function (data) { ratings = data || {}; })
      .catch(function () { ratings = {}; });
  }

  function searchViewport() {
    var seq = ++searchSeq;
    var enabled = CATEGORIES.filter(function (c) {
      return document.getElementById('chk-' + c.key).checked;
    });
    var minRating = parseFloat(document.getElementById('min-rating').value);

    setStatus('검색 중…');
    clearMarkers();
    if (!enabled.length) { setStatus('0곳'); return; }

    var pending = enabled.length;
    var results = [];

    enabled.forEach(function (cat) {
      collectCategory(cat, seq, function (list) {
        if (seq !== searchSeq) return;
        results = results.concat(list);
        if (--pending === 0) render(results, minRating, seq);
      });
    });
  }

  // 한 카테고리를 최대 MAX_PAGES 페이지까지 수집
  function collectCategory(cat, seq, done) {
    var acc = [];
    places.categorySearch(cat.code, function cb(result, status, pagination) {
      if (seq !== searchSeq) return;
      if (status === kakao.maps.services.Status.OK) {
        result.forEach(function (p) { p._cat = cat; });
        acc = acc.concat(result);
        if (pagination.hasNextPage && pagination.current < MAX_PAGES) {
          pagination.nextPage();
          return;
        }
      }
      done(acc);
    }, { useMapBounds: true });
  }

  function render(list, minRating, seq) {
    if (seq !== searchSeq) return;
    var shown = 0;
    var newMarkers = [];

    list.forEach(function (p) {
      var r = ratings[p.id];
      if (minRating > 0 && !(r && r.kakao && r.kakao.rating >= minRating)) return;
      shown++;

      var pos = new kakao.maps.LatLng(p.y, p.x);
      var marker = new kakao.maps.Marker({ position: pos, title: p.place_name });
      kakao.maps.event.addListener(marker, 'click', function () { showCard(p, r, pos); });
      newMarkers.push(marker);

      if (r && (r.kakao || r.naver)) {
        var label = new kakao.maps.CustomOverlay({
          position: pos,
          content: labelHtml(r),
          yAnchor: 1,
          zIndex: 2,
        });
        label.setMap(map);
        markers.push(label);
      }
    });

    clusterer.addMarkers(newMarkers);
    markers = markers.concat(newMarkers);
    setStatus(shown + '곳');
  }

  function labelHtml(r) {
    var parts = [];
    if (r.kakao) parts.push('<span class="k">K ' + r.kakao.rating.toFixed(1) + '</span>');
    if (r.naver && r.naver.rating) parts.push('<span class="n">N ' + r.naver.rating.toFixed(1) + '</span>');
    return '<div class="marker-label">' + parts.join(' · ') + '</div>';
  }

  function showCard(p, r, pos) {
    closeCard();
    var el = document.createElement('div');
    el.className = 'place-card';
    el.innerHTML =
      '<button class="close" aria-label="닫기">×</button>' +
      '<h3>' + esc(p.place_name) + '</h3>' +
      '<div class="category">' + esc(p.category_name ? p.category_name.split('>').pop().trim() : '') + '</div>' +
      '<div class="address">' + esc(p.road_address_name || p.address_name || '') + '</div>' +
      ratingRow('kakao', '카카오', r && r.kakao) +
      ratingRow('naver', '네이버', r && r.naver) +
      '<div class="links">' +
      '<a class="kakao" href="' + esc(p.place_url) + '" target="_blank" rel="noopener">카카오맵</a>' +
      '<a class="naver" href="https://map.naver.com/p/search/' + encodeURIComponent(p.place_name) + '" target="_blank" rel="noopener">네이버지도</a>' +
      '</div>';

    var overlay = new kakao.maps.CustomOverlay({ position: pos, content: el, yAnchor: 0, zIndex: 10 });
    overlay.setMap(map);
    el.querySelector('.close').addEventListener('click', closeCard);
    openCard = overlay;
  }

  function ratingRow(cls, name, data) {
    var body;
    if (data && data.rating) {
      body = '<span class="score">★ ' + data.rating.toFixed(1) + '</span>' +
        '<span class="count">(' + (data.count || 0) + ')</span>';
    } else if (data && data.visitorReviews != null) {
      body = '<span class="count">방문자리뷰 ' + data.visitorReviews +
        (data.blogReviews != null ? ' · 블로그 ' + data.blogReviews : '') + '</span>';
    } else {
      body = '<span class="none">평점 캐시 없음</span>';
    }
    return '<div class="rating-row"><span class="src ' + cls + '">' + name + '</span>' + body + '</div>';
  }

  function closeCard() {
    if (openCard) { openCard.setMap(null); openCard = null; }
  }

  function clearMarkers() {
    closeCard();
    clusterer.clear();
    markers.forEach(function (m) { m.setMap(null); });
    markers = [];
  }

  function setStatus(text) {
    document.getElementById('status').textContent = text;
  }

  function saveViewport() {
    try {
      var c = map.getCenter();
      localStorage.setItem('apimap.viewport', JSON.stringify({
        lat: c.getLat(), lng: c.getLng(), level: map.getLevel(),
      }));
    } catch (e) { /* 저장 실패는 무시 */ }
  }

  function loadViewport() {
    try {
      var v = JSON.parse(localStorage.getItem('apimap.viewport'));
      if (v && v.lat && v.lng) return v;
    } catch (e) { /* 무시 */ }
    return {
      lat: CONFIG.DEFAULT_CENTER.lat,
      lng: CONFIG.DEFAULT_CENTER.lng,
      level: CONFIG.DEFAULT_LEVEL,
    };
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }
})();
