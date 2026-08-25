/**
 * API Map — 음식점·카페 평점 지도 (Spring Boot + Leaflet)
 *
 * 지도를 움직이면 백엔드 /api/places 가 지도 중심 주변의 장소를
 * 카카오 평점(실시간)과 네이버 평점(오프라인 수집분)으로 반환한다.
 */
(function () {
  'use strict';

  var DEFAULT_VIEW = { lat: 37.49795, lng: 127.02758, zoom: 16 };

  var saved = loadViewport();
  var map = L.map('map').setView([saved.lat, saved.lng], saved.zoom);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);

  var layer = L.layerGroup().addTo(map);
  var fetchSeq = 0;
  var lastPlaces = [];

  var timer = null;
  map.on('moveend', function () {
    saveViewport();
    clearTimeout(timer);
    timer = setTimeout(refresh, 400);
  });
  document.querySelectorAll('#controls input').forEach(function (el) {
    el.addEventListener('change', refresh);
  });
  document.getElementById('min-rating').addEventListener('change', function () {
    render(lastPlaces); // 필터만 바뀌면 재요청 없이 다시 그림
  });

  refresh();

  function refresh() {
    var categories = [];
    if (document.getElementById('chk-food').checked) categories.push('food');
    if (document.getElementById('chk-cafe').checked) categories.push('cafe');
    if (!categories.length) {
      lastPlaces = [];
      render(lastPlaces);
      return;
    }

    var seq = ++fetchSeq;
    var b = map.getBounds();
    setStatus('검색 중…');
    fetch('/api/places?swLat=' + b.getSouth().toFixed(6) + '&swLng=' + b.getWest().toFixed(6) +
          '&neLat=' + b.getNorth().toFixed(6) + '&neLng=' + b.getEast().toFixed(6) +
          '&categories=' + categories.join(','))
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (data) {
        if (seq !== fetchSeq) return;
        lastPlaces = data.places || [];
        render(lastPlaces, data.region);
      })
      .catch(function (e) {
        if (seq !== fetchSeq) return;
        setStatus('오류: ' + e.message);
      });
  }

  function render(places, region) {
    layer.clearLayers();
    var minRating = parseFloat(document.getElementById('min-rating').value);
    var shown = 0;

    places.forEach(function (p) {
      if (minRating > 0 && !(p.kakao && p.kakao.rating >= minRating)) return;
      shown++;

      var isCafe = /카페|커피|디저트|베이커리|찻집/.test(p.category);
      var marker = L.marker([p.lat, p.lng], {
        icon: L.divIcon({
          className: '',
          html: markerHtml(p, isCafe),
          iconSize: null,
          iconAnchor: [0, 12],
        }),
      });
      marker.bindPopup(popupHtml(p), { maxWidth: 300 });
      layer.addLayer(marker);
    });

    setStatus((region ? region + ' · ' : '') + shown + '곳');
  }

  function markerHtml(p, isCafe) {
    var scores = [];
    if (p.kakao) scores.push('<span class="k">K ' + p.kakao.rating.toFixed(1) + '</span>');
    var nRating = p.naver && p.naver.rating;
    if (nRating) scores.push('<span class="n">N ' + Number(nRating).toFixed(1) + '</span>');
    return '<div class="poi ' + (isCafe ? 'cafe' : 'food') + '">' +
      '<span class="dot"></span>' +
      '<span class="poi-name">' + esc(shorten(p.name, 12)) + '</span>' +
      (scores.length ? '<span class="poi-score">' + scores.join(' ') + '</span>' : '') +
      '</div>';
  }

  function popupHtml(p) {
    return '<div class="place-popup">' +
      '<h3>' + esc(p.name) + '</h3>' +
      '<div class="category">' + esc(p.category) + '</div>' +
      '<div class="address">' + esc(p.address) + '</div>' +
      ratingRow('kakao', '카카오', p.kakao) +
      ratingRow('naver', '네이버', p.naver) +
      '<div class="links">' +
      '<a class="kakao" href="' + esc(p.placeUrl) + '" target="_blank" rel="noopener">카카오맵</a>' +
      '<a class="naver" href="https://map.naver.com/p/search/' + encodeURIComponent(p.name) +
      '" target="_blank" rel="noopener">네이버지도</a>' +
      '</div></div>';
  }

  function ratingRow(cls, label, data) {
    var body;
    if (data && data.rating) {
      body = '<span class="score">★ ' + Number(data.rating).toFixed(1) + '</span>' +
        '<span class="count">(' + (data.count || 0) + ')</span>' +
        (data.reviews ? '<span class="count">리뷰 ' + data.reviews + '</span>' : '');
    } else if (data && data.visitorReviews != null) {
      body = '<span class="count">방문자리뷰 ' + data.visitorReviews +
        (data.blogReviews != null ? ' · 블로그 ' + data.blogReviews : '') + '</span>';
    } else {
      body = '<span class="none">' + (cls === 'naver' ? '수집 전' : '평점 없음') + '</span>';
    }
    return '<div class="rating-row"><span class="src ' + cls + '">' + label + '</span>' + body + '</div>';
  }

  function setStatus(text) {
    document.getElementById('status').textContent = text;
  }

  function shorten(s, n) {
    return s.length > n ? s.slice(0, n) + '…' : s;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function saveViewport() {
    try {
      var c = map.getCenter();
      localStorage.setItem('apimap.viewport',
        JSON.stringify({ lat: c.lat, lng: c.lng, zoom: map.getZoom() }));
    } catch (e) { /* 무시 */ }
  }

  function loadViewport() {
    try {
      var v = JSON.parse(localStorage.getItem('apimap.viewport'));
      if (v && v.lat && v.lng) return v;
    } catch (e) { /* 무시 */ }
    return DEFAULT_VIEW;
  }
})();
