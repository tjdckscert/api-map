/**
 * API Map — 음식점·카페 평점 지도 (Spring Boot + Leaflet)
 *
 * - 지도 이동 시 /api/places 가 뷰포트(bbox) 안의 장소를 카카오 평점과 함께 반환
 * - 검색창은 /api/search 로 장소/지역을 찾아 지도를 이동
 * - 네이버 평점은 오프라인 수집분이 있으면 병기
 */
(function () {
  'use strict';

  var DEFAULT_VIEW = { lat: 37.49795, lng: 127.02758, zoom: 16 };

  var saved = loadViewport();
  var map = L.map('map', { zoomControl: false }).setView([saved.lat, saved.lng], saved.zoom);
  L.control.zoom({ position: 'bottomright' }).addTo(map);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
    maxZoom: 20,
    subdomains: 'abcd',
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a> &copy; <a href="https://carto.com/attributions">CARTO</a>',
  }).addTo(map);

  var layer = L.layerGroup().addTo(map);
  var fetchSeq = 0;
  var lastPlaces = [];
  var focusId = null; // 검색으로 이동한 장소: 렌더 후 팝업 자동 오픈

  // ── 지도 이벤트 ──────────────────────────────
  var timer = null;
  map.on('moveend', function () {
    saveViewport();
    clearTimeout(timer);
    timer = setTimeout(refresh, 400);
  });
  document.querySelectorAll('.control-group input').forEach(function (el) {
    el.addEventListener('change', refresh);
  });
  document.getElementById('min-rating').addEventListener('change', function () {
    render(lastPlaces);
  });

  refresh();

  // ── 뷰포트 검색 ──────────────────────────────
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
    var focusMarker = null;

    places.forEach(function (p) {
      var isFocus = p.id === focusId;
      if (!isFocus && minRating > 0 && !(p.kakao && p.kakao.rating >= minRating)) return;
      shown++;

      var isCafe = /카페|커피|디저트|베이커리|찻집|브런치/.test(p.category);
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
      if (isFocus) focusMarker = marker;
    });

    setStatus((region ? region + ' · ' : '') + shown + '곳');

    if (focusMarker) {
      focusMarker.openPopup();
      focusId = null;
    }
  }

  // ── 장소/지역 검색창 ──────────────────────────
  var searchInput = document.getElementById('search-input');
  var searchClear = document.getElementById('search-clear');
  var searchResults = document.getElementById('search-results');
  var searchTimer = null;

  searchInput.addEventListener('input', function () {
    searchClear.hidden = !searchInput.value;
    clearTimeout(searchTimer);
    if (searchInput.value.trim().length < 2) { hideResults(); return; }
    searchTimer = setTimeout(runSearch, 300);
  });
  searchInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { clearTimeout(searchTimer); runSearch(); }
    if (e.key === 'Escape') hideResults();
  });
  searchClear.addEventListener('click', function () {
    searchInput.value = '';
    searchClear.hidden = true;
    hideResults();
    searchInput.focus();
  });
  document.addEventListener('click', function (e) {
    if (!document.getElementById('searchbar').contains(e.target)) hideResults();
  });

  function runSearch() {
    var q = searchInput.value.trim();
    if (q.length < 2) return;
    fetch('/api/search?q=' + encodeURIComponent(q))
      .then(function (r) { return r.json(); })
      .then(function (data) { showResults(data.places || []); })
      .catch(function () { showResults([]); });
  }

  function showResults(places) {
    searchResults.innerHTML = '';
    if (!places.length) {
      var li = document.createElement('li');
      li.className = 'r-empty';
      li.textContent = '검색 결과가 없습니다';
      searchResults.appendChild(li);
    }
    places.slice(0, 10).forEach(function (p) {
      var li = document.createElement('li');
      li.innerHTML =
        '<div class="r-name">' + esc(p.name) + '<span class="r-cat">' + esc(p.category) + '</span></div>' +
        '<div class="r-sub">' +
        (p.kakao ? '<span class="r-rating">★ ' + p.kakao.rating.toFixed(1) + '</span>' : '') +
        '<span>' + esc(p.address) + '</span></div>';
      li.addEventListener('click', function () { goTo(p); });
      searchResults.appendChild(li);
    });
    searchResults.hidden = false;
  }

  function goTo(p) {
    hideResults();
    focusId = p.id;
    map.setView([p.lat, p.lng], Math.max(map.getZoom(), 17));
    // moveend → refresh → render 에서 focusId 팝업 자동 오픈
  }

  function hideResults() {
    searchResults.hidden = true;
    searchResults.innerHTML = '';
  }

  // ── 마커/팝업 렌더링 ──────────────────────────
  function markerHtml(p, isCafe) {
    var scores = [];
    if (p.kakao) scores.push('<span class="k">' + p.kakao.rating.toFixed(1) + '</span>');
    var nRating = p.naver && p.naver.rating;
    if (nRating) scores.push('<span class="n">' + Number(nRating).toFixed(1) + '</span>');
    return '<div class="poi ' + (isCafe ? 'cafe' : 'food') + '">' +
      '<span class="dot"></span>' +
      '<span class="poi-name">' + esc(shorten(p.name, 12)) + '</span>' +
      (scores.length ? '<span class="poi-score">★' + scores.join('·') + '</span>' : '') +
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

  // ── 유틸 ─────────────────────────────────────
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
