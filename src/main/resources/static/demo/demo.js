/*
 * 데모 쇼핑몰: 측정 경로(⑦ 노출, ⑧ 클릭)가 브라우저에서 어떻게 출발하는지 보여주는 측정 장치.
 *
 * 지키는 규칙
 * - RENDERED: 광고 카드를 DOM에 그리는 즉시 보냅니다(IAB 노출 정의: 렌더링 시작).
 * - VIEWABLE: 광고 카드의 50% 이상이 1초 연속 화면에 있으면 한 번 보냅니다(IAB/MRC 뷰어빌리티).
 *             중간에 50% 아래로 내려가면 1초를 처음부터 다시 셉니다.
 * - CLICK: 세 가지 방법 중 하나로 보냅니다. 방법에 따라 유실이 달라지는 것을 비교하는 것이 목적입니다.
 *     redirect  광고 링크 자체가 서버의 클릭 URL. 서버가 기록한 뒤 상품 페이지로 보냄. 브라우저 JS에 기대지 않음
 *     beacon    navigator.sendBeacon 후 바로 이동. 페이지가 닫혀도 브라우저가 전송을 끝까지 맡음
 *     fetch     SDK처럼 모아서 150ms 뒤 fetch. 그 전에 페이지가 바뀌면 JS가 사라져 전송되지 않음(화면 전환 경쟁 상태)
 * - "브라우저가 보낸 수"는 localStorage에 남겨 페이지를 옮겨 다녀도 유지합니다. 서버 숫자(/events/stats)와 비교하는 것이
 *   클라이언트 집계와 서버 집계의 대사입니다(CLAUDE.md 3-6).
 */
(() => {
  const $ = (id) => document.getElementById(id);
  const TYPES = ['RENDERED', 'VIEWABLE', 'CLICK'];
  const store = {
    get(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } },
    set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 저장소를 못 써도 데모는 동작 */ } },
  };

  function bump(type) {
    const sent = store.get('demo.sent', {});
    sent[type] = (sent[type] || 0) + 1;
    store.set('demo.sent', sent);
  }

  function log(text) {
    const line = document.createElement('div');
    line.textContent = new Date().toLocaleTimeString() + '  ' + text;
    $('log').prepend(line);
    const saved = store.get('demo.log', []);
    saved.unshift(line.textContent);
    store.set('demo.log', saved.slice(0, 30));
  }

  function beacon(url) {
    // sendBeacon은 응답을 기다리지 않고, 페이지가 닫혀도 브라우저가 전송을 맡습니다.
    return navigator.sendBeacon(url);
  }

  // ---------- ⑦ 노출: RENDERED, VIEWABLE ----------
  const visibleTimers = new Map();
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      const card = entry.target;
      if (card.dataset.viewableSent) continue;
      if (entry.intersectionRatio >= 0.5) {
        if (!visibleTimers.has(card)) {
          const started = performance.now();
          const tick = setInterval(() => {
            card.querySelector('.view').textContent = '50% 이상 보이는 중 ' + ((performance.now() - started) / 1000).toFixed(1) + '초';
          }, 100);
          const timer = setTimeout(() => {
            clearInterval(tick);
            visibleTimers.delete(card);
            card.dataset.viewableSent = '1';
            beacon('/events/impression?type=VIEWABLE&token=' + encodeURIComponent(card.dataset.token));
            bump('VIEWABLE');
            log('VIEWABLE 광고 #' + card.dataset.adId + ' (50% 이상 1초)');
            const view = card.querySelector('.view');
            view.textContent = 'VIEWABLE 보냄';
            view.classList.add('sent');
          }, 1000);
          visibleTimers.set(card, { timer, tick });
        }
      } else if (visibleTimers.has(card)) {
        const { timer, tick } = visibleTimers.get(card);
        clearTimeout(timer);
        clearInterval(tick);
        visibleTimers.delete(card);
        card.querySelector('.view').textContent = '렌더링됨, 아직 viewable 아님(50% 미만으로 내려가 다시 셈)';
      }
    }
  }, { threshold: [0, 0.25, 0.5, 0.75, 1] });

  // ---------- ⑧ 클릭 ----------
  function productUrl(productId, token) {
    const chain = $('chain').checked && token;
    return '/demo/product.html?productId=' + productId + (chain ? '&adToken=' + encodeURIComponent(token) : '');
  }

  function clickAd(item) {
    const mode = document.querySelector('input[name=mode]:checked').value;
    const token = encodeURIComponent(item.token);
    bump('CLICK');
    log('CLICK 광고 #' + item.adId + ' (' + mode + ')');
    if (mode === 'redirect') {
      location.href = '/events/click?token=' + token + '&chain=' + $('chain').checked;
      return;
    }
    const clickUrl = '/events/click?token=' + token + '&clickId=' + crypto.randomUUID() + '&eventTime=' + Date.now();
    if (mode === 'beacon') {
      beacon(clickUrl);
    } else {
      setTimeout(() => fetch(clickUrl, { method: 'POST' }), 150); // 페이지가 먼저 바뀌면 이 타이머는 실행되지 않습니다
    }
    location.href = productUrl(item.productId, item.token);
  }

  // ---------- 서빙 요청과 그리기 ----------
  async function loadList() {
    const params = new URLSearchParams({
      category: $('category').value, userId: $('userId').value, personalize: $('personalize').checked,
    });
    store.set('demo.request', { userId: $('userId').value, category: $('category').value, personalize: $('personalize').checked });
    const res = await fetch('/ads?' + params);
    const body = await res.json();
    const winners = (body.auction || []).filter((a) => a.result === 'WON').map((a) => '#' + a.adId + ' ' + a.brand + ' ' + a.price + '원');
    const rejected = (body.auction || []).filter((a) => a.result.startsWith('REJECTED')).map((a) => '#' + a.adId + ' ' + a.result.replace('REJECTED:', ''));
    $('outcome').textContent = '결과 ' + body.outcome + ' / ' + body.elapsedMs + 'ms / 최근 본 브랜드 ' + (body.recentBrand || '없음')
      + ' / 낙찰 ' + (winners.join(', ') || '없음') + (rejected.length ? ' / 제외 ' + rejected.join(', ') : '');

    observer.disconnect();
    visibleTimers.forEach(({ timer, tick }) => { clearTimeout(timer); clearInterval(tick); });
    visibleTimers.clear();
    $('list').replaceChildren();

    for (const item of body.items) {
      const card = document.createElement('div');
      card.className = 'card' + (item.type === 'AD' ? ' ad' : '');
      card.innerHTML = '<span class="pos"></span> <span class="badge" hidden>광고</span>'
        + '<div class="name"></div><div class="meta"></div><div class="view"></div>';
      card.querySelector('.pos').textContent = item.position + '번째 칸';
      card.querySelector('.name').textContent = item.name;
      card.querySelector('.meta').textContent = item.brand + ' · ' + item.price.toLocaleString() + '원';
      if (item.type === 'AD') {
        card.querySelector('.badge').hidden = false;
        card.querySelector('.meta').textContent += ' · 광고 #' + item.adId + ' · 낙찰 CPC ' + item.cpc + '원';
        card.dataset.token = item.token;
        card.dataset.adId = item.adId;
        card.onclick = () => clickAd(item);
      } else {
        card.onclick = () => { location.href = productUrl(item.productId, null); };
      }
      $('list').append(card);

      if (item.type === 'AD') {
        beacon('/events/impression?type=RENDERED&token=' + encodeURIComponent(item.token));
        bump('RENDERED');
        log('RENDERED 광고 #' + item.adId + ' (' + item.position + '번째 칸)');
        card.querySelector('.view').textContent = '렌더링됨, 아직 viewable 아님';
        observer.observe(card);
      }
    }
    setTimeout(refreshCompare, 300);
  }

  // ---------- 브라우저 vs 서버 비교 ----------
  async function serverCounts() {
    const stats = await (await fetch('/events/stats')).json();
    const out = {};
    for (const t of TYPES) {
      const c = stats.events[t];
      out[t] = { arrived: c.accepted + c.duplicate + c.givt, accepted: c.accepted };
    }
    return out;
  }

  async function refreshCompare() {
    const sent = store.get('demo.sent', {});
    const base = store.get('demo.baseline', null);
    const now = await serverCounts();
    $('compare').replaceChildren();
    for (const t of TYPES) {
      const b = base ? base[t] : { arrived: 0, accepted: 0 };
      const arrived = now[t].arrived - b.arrived;
      const accepted = now[t].accepted - b.accepted;
      const s = sent[t] || 0;
      const row = document.createElement('tr');
      const loss = s - arrived;
      row.innerHTML = '<td></td><td></td><td></td><td></td><td></td>';
      [t, s, arrived, loss, accepted].forEach((v, i) => { row.children[i].textContent = v; });
      if (loss > 0) row.children[3].className = 'loss';
      $('compare').append(row);
    }
  }

  $('measureStart').onclick = async () => {
    store.set('demo.baseline', await serverCounts());
    store.set('demo.sent', {});
    store.set('demo.log', []);
    $('log').replaceChildren();
    log('측정 시작: 지금부터 브라우저와 서버 숫자를 0으로 보고 셉니다(서버의 다른 사용자 이벤트도 섞일 수 있음)');
    refreshCompare();
  };
  $('measureRefresh').onclick = refreshCompare;
  $('reload').onclick = loadList;

  const saved = store.get('demo.request', null);
  if (saved) {
    $('userId').value = saved.userId;
    $('category').value = saved.category;
    $('personalize').checked = saved.personalize;
  }
  for (const line of store.get('demo.log', []).slice().reverse()) {
    const div = document.createElement('div');
    div.textContent = line;
    $('log').prepend(div);
  }
  loadList();
})();
