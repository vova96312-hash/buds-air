/* Buds Air — управление наушниками realme / OPPO / OnePlus */
(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = {
    get(k, d = null) { try { const v = localStorage.getItem('buds.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('buds.' + k, JSON.stringify(v)); } catch {} },
  };
  const haptic = () => { try { navigator.vibrate && navigator.vibrate(8); } catch {} };
  const Cap = window.Capacitor;
  const native = !!(Cap && Cap.isNativePlatform && Cap.isNativePlatform());
  const plugin = (n) => {
    try { if (Cap && Cap.registerPlugin) return Cap.registerPlugin(n); } catch {}
    return (Cap && Cap.Plugins && Cap.Plugins[n]) || null;
  };
  const timeout = (p, ms = 3000) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('Нет ответа от телефона')), ms))]);
  const MODELS = window.BUDS_MODELS || {};

  // ---------------------------------------------------------------- mock (проверка в браузере)
  const Mock = (() => {
    const st = { connected: false, connecting: false, name: null, address: null, productId: null, firmware: null,
      batteryLeft: 82, batteryRight: 79, batteryCase: 54, chargingLeft: false, chargingRight: false, chargingCase: true,
      wearLeft: 3, wearRight: 3, wearCase: 4, ancMode: 0x08, ancLevel: 0x08, smartLevel: 0, holdMask: 0x0B, eqPreset: 0, alertVolume: 6,
      features: {}, commands: [], gestures: {}, devices: [] };
    const ls = []; const emit = () => ls.forEach((f) => f(JSON.parse(JSON.stringify(st))));
    const later = (fn, ms = 150) => new Promise((r) => setTimeout(() => { fn(); emit(); r(); }, ms));
    return {
      addListener: (e, f) => { ls.push(f); return { remove() {} }; },
      ensurePermission: async () => ({ granted: true }),
      getBonded: async () => ({ devices: [{ name: 'realme Buds Air8', address: 'AA:BB:CC:DD:EE:FF', connected: true }, { name: 'JBL Flip 6', address: '11:22:33:44:55:66' }] }),
      autoConnect: async () => ({ address: null }),
      connect: async ({ address }) => { st.connecting = true; emit(); await later(() => {
        Object.assign(st, { connecting: false, connected: true, address, name: 'realme Buds Air8', productId: '066812', firmware: '1.0.3.8',
          features: { 4: 1, 6: 0, 9: 0, 17: 1, 24: 0, 26: 1, 27: 0, 29: 1 }, commands: [0x0405, 0x0406, 0x0427, 0x0112, 0x0418],
          gestures: { '1:1:2': 1, '1:1:3': 5, '1:1:4': 8, '2:1:2': 1, '2:1:3': 6, '2:1:4': 8 },
          devices: [{ mac: '4C:11:22:33:44:55', name: 'Galaxy S24', connected: true, thisPhone: true, main: true, playing: true }, { mac: '9A:00:11:22:33:44', name: 'ThinkPad', connected: false }] });
      }, 800); return st; },
      disconnect: async () => { st.connected = false; emit(); },
      getState: async () => JSON.parse(JSON.stringify(st)),
      refresh: async () => later(() => {}),
      setAnc: async ({ mask }) => later(() => { st.ancMode = mask; if ([4, 8, 16, 32].includes(mask)) st.ancLevel = mask; if (mask === 32) st.smartLevel = 16; }),
      setHoldMask: async ({ mask }) => later(() => { st.holdMask = mask; }),
      setFeature: async ({ id, on }) => later(() => { st.features[id] = on ? 1 : 0; }),
      find: async () => later(() => {}),
      fitTest: async ({ start }) => { if (start) { st.fitLeft = null; st.fitRight = null; emit(); setTimeout(() => { st.fitLeft = 'good'; st.fitRight = 'average'; emit(); }, 2500); } },
      setEqPreset: async ({ id }) => later(() => { st.eqPreset = id; }),
      setCustomEq: async () => later(() => { st.eqAcked = true; }),
      setAlertVolume: async ({ level }) => later(() => { st.alertVolume = level; }),
      setGesture: async ({ dev, btn, act, fn }) => later(() => { st.gestures[`${dev}:${btn}:${act}`] = fn; }),
      queryDevices: async () => later(() => {}),
      send: async () => later(() => {}),
      getLog: async () => ({ lines: ['10:40:01 connect 00001101-0000-1000-8000-00805f9b34fb', '10:40:02 connected', '10:40:02 → aa 07 00 00 00 01 01 00 00', '10:40:02 ← aa 10 00 04 00 81 01 09 00 00 ff 77 5a ea 67 0e 20 07'] }),
      updateWidget: async () => {},
    };
  })();
  const Buds = native ? plugin('Buds') : Mock;

  // ---------------------------------------------------------------- icons
  const I = {
    home: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/></svg>',
    eq: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/></svg>',
    hand: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 11V6a2 2 0 0 0-4 0v5M14 10V4a2 2 0 0 0-4 0v6M10 10.5V6a2 2 0 0 0-4 0v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/></svg>',
    gear: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',
    refresh: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>',
    anc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="7" opacity=".6"/><path d="M12 1v2M12 21v2M1 12h2M21 12h2"/></svg>',
    trans: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M2 12c2-3 4-3 6 0s4 3 6 0 4-3 6 0"/><path d="M2 17c2-3 4-3 6 0s4 3 6 0 4-3 6 0" opacity=".5"/><path d="M2 7c2-3 4-3 6 0s4 3 6 0 4-3 6 0" opacity=".5"/></svg>',
    off: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="8"/><path d="M6 18 18 6"/></svg>',
    auto: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v3M12 18v3M3 12h3M18 12h3"/><circle cx="12" cy="12" r="5"/><path d="M10 12l1.5 1.5L14 11"/></svg>',
    game: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 11h4M8 9v4M15 12h.01M18 10h.01"/><path d="M17.32 5H6.68a4 4 0 0 0-3.98 3.59L2 15a3 3 0 0 0 5.24 2.06L9 15h6l1.76 2.06A3 3 0 0 0 22 15l-.7-6.41A4 4 0 0 0 17.32 5z"/></svg>',
    multi: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="13" height="10" rx="2"/><rect x="15" y="9" width="7" height="12" rx="2"/><path d="M6 18h5"/></svg>',
    spatial: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="2"/><path d="M16.24 7.76a6 6 0 0 1 0 8.49M7.76 16.24a6 6 0 0 1 0-8.49M19.07 4.93a10 10 0 0 1 0 14.14M4.93 19.07a10 10 0 0 1 0-14.14"/></svg>',
    ear: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 8.5a6.5 6.5 0 1 1 13 0c0 6-6 6-6 10a3.5 3.5 0 1 1-7 0"/><path d="M15 8.5a2.5 2.5 0 0 0-5 0v1a2 2 0 1 1 0 4"/></svg>',
    wind: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.7 7.7a2.5 2.5 0 1 1 1.8 4.3H2M9.6 4.6A2 2 0 1 1 11 8H2M12.6 19.4A2 2 0 1 0 14 16H2"/></svg>',
    mic: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M19 10v1a7 7 0 0 1-14 0v-1M12 18v4"/></svg>',
    bass: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M2 12h3l3-8 4 16 3-8h7"/></svg>',
    hires: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
    bell: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>',
    moon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
    bolt: '<svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M13 2 3 14h8l-1 8 10-12h-8z"/></svg>',
    bt: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m7 7 10 10-5 5V2l5 5L7 17"/></svg>',
    head: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 18v-6a9 9 0 0 1 18 0v6"/><path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"/></svg>',
    phone: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/></svg>',
    chev: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>',
    check: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
    x: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>',
    copy: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="3"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
    play: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M7 4v16l13-8z"/></svg>',
    stop: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  };

  // ---------------------------------------------------------------- модель
  const S = { st: {}, tab: 'home', side: 1, eq: store.get('eq', [0, 0, 0, 0, 0, 0]), finding: false, noise: null };
  const model = () => MODELS[(S.st.productId || store.get('product') || '').toUpperCase()] || null;
  const feat = (id) => (S.st.features || {})[id];
  const hasFeat = (id) => feat(id) !== undefined;
  const hasCmd = (c) => { const cm = S.st.commands || []; return !cm.length || cm.includes(c); };

  // режимы шумоподавления: modeType -> название
  const MT = { 1: 'Выкл', 2: 'Прозрачность', 3: 'Слабый', 4: 'Глубокий', 5: 'Шумодав', 7: 'Умный', 8: 'Средний', 10: 'Адаптивный' };
  function ancTable() {
    const m = model();
    // по умолчанию — таблица realme: Выкл 0, Прозрачность 1, Шумодав 3
    const top = (m && m.anc) || [[5, 3], [1, 0], [2, 1]];
    const lv = (m && m.lv) || [];
    const order = { 1: 0, 5: 1, 10: 2, 2: 3 };
    const tops = top.map(([t, pi]) => ({ t, bit: 1 << pi })).filter((x) => order[x.t] !== undefined).sort((a, b) => order[a.t] - order[b.t]);
    const lorder = { 7: 0, 3: 1, 8: 2, 4: 3 };
    const levels = lv.map(([t, pi]) => ({ t, bit: 1 << pi })).filter((x) => lorder[x.t] !== undefined).sort((a, b) => lorder[a.t] - lorder[b.t]);
    return { tops, levels };
  }
  function ancKind(mask) {
    const { tops, levels } = ancTable();
    if (levels.some((l) => l.bit === mask)) return 5;
    const t = tops.find((x) => x.bit === mask);
    return t ? t.t : null;
  }

  // жесты: маска вариантов -> код функции
  const SUPPORT = [[512, 0, 'Ничего'], [4, 1, 'Пауза / воспроизведение'], [32, 5, 'Предыдущий трек'], [64, 6, 'Следующий трек'],
    [8, 11, 'Громче'], [16, 12, 'Тише'], [128, 8, 'Режимы шумоподавления'], [1, 3, 'Голосовой ассистент'], [8192, 17, 'Игровой режим'],
    [4096, 13, 'Переключить устройство'], [256, 9, 'Избранная музыка']];
  const FN_NAME = Object.fromEntries(SUPPORT.map(([, f, n]) => [f, n]));
  FN_NAME[2] = 'Музыка'; FN_NAME[7] = 'Громкость (свайп)'; FN_NAME[10] = 'Трек (свайп)';
  function gestureRows() {
    const m = model();
    const generic = [512, 4, 32, 64, 8, 16, 128, 1, 8192].reduce((a, b) => a | b, 0);
    if (!m || !m.control) return [{ act: 2, title: 'Двойное касание', mask: generic }, { act: 3, title: 'Тройное касание', mask: generic }, { act: 4, title: 'Касание и удержание', mask: generic }];
    const rows = [];
    for (const c of m.control) {
      const act = c.action >= 16 && c.action <= 18 ? c.action - 15 : c.action;
      if (act === 1) rows.push({ act: 1, title: 'Одно касание', mask: c.support });
      if (act === 2) rows.push({ act: 2, title: 'Двойное касание', mask: c.support });
      if (act === 3) rows.push({ act: 3, title: 'Тройное касание', mask: c.support });
      if (act === 4 && c.support) rows.push({ act: 4, title: 'Касание и удержание', mask: c.support });
    }
    if (m.longPressType && !rows.some((r) => r.act === 4)) rows.push({ act: 4, title: 'Касание и удержание', mask: m.longPressType });
    return rows;
  }
  const holdCycleRow = () => { const m = model(); return m && m.control ? m.control.find((c) => [11, 20, 27].includes(c.action)) : { action: 11, minSelectCount: 2 }; };

  // пресеты эквалайзера
  const EQ_NAMES = { 101: ['Оригинальный', 'Чистый звук без обработки'], 102: ['Глубокий бас', 'Мощные низкие частоты'], 103: ['Чистый голос', 'Речь и подкасты'], 104: ['Чистый бас', 'Плотный, но аккуратный бас'],
    1: ['Сбалансированный', ''], 2: ['Чистый вокал', ''], 3: ['Бас', ''], 4: ['Классика', ''] };
  function eqPresets() {
    const m = model();
    const list = (m && m.eq) || [[101, 0], [102, 1], [103, 2], [104, 3]];
    return list.map(([t, id]) => ({ id, t, name: (EQ_NAMES[t] || ['Пресет ' + (id + 1)])[0], sub: (EQ_NAMES[t] || ['', ''])[1] })).sort((a, b) => a.id - b.id);
  }

  // ---------------------------------------------------------------- ui helpers
  let toastT;
  function toast(msg, bad = false) {
    const el = $('#toast'); el.classList.toggle('bad', bad);
    $('.ti', el).innerHTML = bad ? I.x : I.check; $('.tt', el).textContent = msg;
    el.classList.add('show'); clearTimeout(toastT); toastT = setTimeout(() => el.classList.remove('show'), 2400);
  }
  const overlayStack = [];
  function openSheet(html, mount) {
    const root = $('#overlay');
    const bd = document.createElement('div'); bd.className = 'backdrop';
    const sh = document.createElement('div'); sh.className = 'sheet'; sh.innerHTML = '<div class="grip"></div>' + html;
    root.append(bd, sh);
    const close = () => { bd.classList.remove('show'); sh.classList.remove('show'); setTimeout(() => { bd.remove(); sh.remove(); }, 380); const i = overlayStack.indexOf(close); if (i >= 0) overlayStack.splice(i, 1); sh.dispatchEvent(new Event('closed')); };
    bd.onclick = close;
    let y0 = null, dy = 0;
    sh.addEventListener('touchstart', (e) => { if (sh.scrollTop <= 0 && !e.target.closest('input[type=range]')) { y0 = e.touches[0].clientY; dy = 0; sh.style.transition = 'none'; } }, { passive: true });
    sh.addEventListener('touchmove', (e) => { if (y0 === null) return; dy = Math.max(0, e.touches[0].clientY - y0); sh.style.transform = `translateY(${dy}px)`; }, { passive: true });
    sh.addEventListener('touchend', () => { if (y0 === null) return; sh.style.transition = ''; sh.style.transform = ''; if (dy > 110) close(); y0 = null; });
    overlayStack.push(close);
    requestAnimationFrame(() => { bd.classList.add('show'); sh.classList.add('show'); });
    mount && mount(sh, close);
    return close;
  }
  const WEAR = (s) => (s === 3 || s === 7 ? 'в ухе' : s === 4 || s === 0 ? 'в кейсе' : s === 1 || s === 5 ? 'снят' : '');
  function ring(v, id, label, charging, wear) {
    const r = 29, c = 2 * Math.PI * r, ok = v >= 0, pct = ok ? v : 0;
    const col = pct <= 20 ? ['#fb7185', '#f59e0b'] : ['#8b5cf6', '#22d3ee'];
    return `<div class="bat ${ok ? '' : 'na'}"><div class="ring"><svg viewBox="0 0 70 70"><defs><linearGradient id="g${id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${col[0]}"/><stop offset="1" stop-color="${col[1]}"/></linearGradient></defs>
      <circle class="track" cx="35" cy="35" r="${r}"/><circle class="val" cx="35" cy="35" r="${r}" stroke="url(#g${id})" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - pct / 100)}"/></svg><b>${ok ? v + '%' : '—'}</b></div>
      <div class="lbl">${charging && ok ? `<span class="bolt">${I.bolt}</span>` : ''}${label}</div>${wear ? `<div class="wear">${wear}</div>` : ''}</div>`;
  }
  function budsArt(kind) {
    const bud = (x, flip, cls) => `<g transform="translate(${x} 18) scale(${flip} 1)"><g class="bud ${cls}">
        <path d="M0 26c0-15 11-26 25-26s23 9 23 22c0 12-8 20-18 22l-6 1v54c0 6-4 10-9 10s-9-4-9-10z" fill="url(#bodyG)" stroke="rgba(255,255,255,.35)" stroke-width="1"/>
        <ellipse cx="27" cy="22" rx="12" ry="11" fill="url(#tipG)"/>
        <path d="M6 40v58" stroke="rgba(255,255,255,.5)" stroke-width="2" stroke-linecap="round" opacity=".5"/></g></g>`;
    const cls = kind === 5 ? 'on' : kind === 2 ? 'transparency' : kind === 1 ? 'off' : 'none';
    return `<div class="buds-art anc-${cls}"><div class="glow"></div><div class="waves"><i></i><i></i><i></i></div>
      <svg viewBox="0 0 260 170"><defs>
        <linearGradient id="bodyG" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f4f4fb"/><stop offset=".55" stop-color="#c9c9de"/><stop offset="1" stop-color="#8e8eb0"/></linearGradient>
        <radialGradient id="tipG" cx=".35" cy=".35"><stop offset="0" stop-color="#4b4b6b"/><stop offset="1" stop-color="#16162a"/></radialGradient>
      </defs>${bud(62, 1, 'l')}${bud(198, -1, 'r')}</svg></div>`;
  }
  const sw = (on) => `<label class="switch" style="pointer-events:none"><input type="checkbox" ${on ? 'checked' : ''}><i></i></label>`;

  // ---------------------------------------------------------------- render
  const app = $('#app');
  function render(anim = false) {
    if (!S.st.address && !store.get('address')) return renderPicker();
    if (!$('.nav')) {
      app.innerHTML = `<div class="screen"><div class="scroll" id="view"></div></div>
        <nav class="nav"><span class="lens"></span>
          <button data-tab="home">${I.home}<span>Главная</span></button>
          <button data-tab="sound">${I.eq}<span>Звук</span></button>
          <button data-tab="gest">${I.hand}<span>Жесты</span></button>
          <button data-tab="more">${I.gear}<span>Ещё</span></button>
        </nav>`;
      $$('.nav [data-tab]').forEach((b) => b.onclick = () => { haptic(); S.tab = b.dataset.tab; render(true); });
      anim = true;
    }
    $$('.nav [data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === S.tab));
    const v = $('#view');
    const html = { home: vHome, sound: vSound, gest: vGest, more: vMore }[S.tab]();
    const keep = v.scrollTop;
    v.innerHTML = `<div>${html}</div>`;
    if (anim) { v.scrollTop = 0; $$('.topbar, .card, .tiles, .section, .presets', v).forEach((el, i) => { el.classList.add('rise'); el.style.animationDelay = Math.min(i, 10) * 50 + 'ms'; }); }
    else v.scrollTop = keep;
    moveLens(anim);
    bind(v);
  }
  function moveLens(anim) {
    const nav = $('.nav'), lens = $('.nav .lens'), b = $(`.nav [data-tab="${S.tab}"]`);
    if (!nav || !lens || !b) return;
    const nr = nav.getBoundingClientRect(), br = b.getBoundingClientRect(), w = Math.min(br.width - 6, 84);
    lens.style.width = w + 'px'; lens.style.transform = `translateX(${br.left - nr.left + (br.width - w) / 2}px)`;
    if (anim) { lens.classList.remove('squish'); void lens.offsetWidth; lens.classList.add('squish'); }
  }
  window.addEventListener('resize', () => moveLens(false));

  function statusPill() {
    const st = S.st;
    if (st.connected) return '<span class="pill status-pill conn"><span class="dot"></span>Подключено</span>';
    if (st.connecting) return '<span class="pill warn">Подключение…</span>';
    return '<span class="pill bad" data-connect style="cursor:pointer">Не подключено</span>';
  }
  const devName = () => S.st.name || store.get('name') || (model() && model().n) || 'Наушники';
  function topbar(title) {
    return `<div class="topbar"><div><h1 style="font-size:26px">${title}</h1><div class="sub">${esc(devName())}</div></div>
      <button class="icon-btn" data-refresh>${I.refresh}</button></div>`;
  }
  function tile(id, ic, cls, title, sub) {
    const on = feat(id) === 1;
    return `<button class="card tile ${on ? 'active' : ''}" data-feat="${id}"><div class="ti-top"><span class="ic ${cls}">${ic}</span>${sw(on)}</div>
      <div><div class="t">${title}</div><div class="s">${sub}</div></div></button>`;
  }

  function vHome() {
    const st = S.st;
    const { tops, levels } = ancTable();
    const kind = ancKind(st.ancMode);
    const cur = tops.findIndex((x) => x.t === kind);
    const icon = { 1: I.off, 5: I.anc, 10: I.auto, 2: I.trans };
    const desc = { 1: 'Обычный режим', 5: 'Внешние звуки приглушены', 10: 'Шумоподавление подстраивается само', 2: 'Слышно, что происходит вокруг' }[kind] || 'Режим ещё не получен от наушников';
    const smartTxt = st.ancMode === 0x20 && st.smartLevel ? ` · сейчас ${(levels.find((l) => l.bit === st.smartLevel) ? MT[levels.find((l) => l.bit === st.smartLevel).t] : '').toLowerCase()}` : '';
    const tiles = [
      [6, I.game, 'v', 'Игровой режим', 'Минимальная задержка'],
      [17, I.multi, 'c', 'Два устройства', 'Телефон + ноутбук'],
      [4, I.ear, 'g', 'Автопауза', 'Пауза, когда снимаешь наушник'],
      [26, I.wind, 'c', 'Шумоподавление ветра', 'Меньше шума на улице'],
      [9, I.mic, 'v', 'Усиление голоса', 'Речь разборчивее'],
    ].filter(([id]) => hasFeat(id) || (!Object.keys(st.features || {}).length && (id === 6 || id === 17)));
    return `<div class="topbar"><div><h1 style="font-size:26px">${esc(devName())}</h1><div class="sub" style="margin-top:6px">${statusPill()}</div></div>
        <button class="icon-btn" data-refresh>${I.refresh}</button></div>
      <div class="card hero buds-hero">
        ${budsArt(st.connected ? kind : null)}
        <div class="bats">${ring(st.batteryLeft ?? -1, 'l', 'Левый', st.chargingLeft, WEAR(st.wearLeft))}${ring(st.batteryRight ?? -1, 'r', 'Правый', st.chargingRight, WEAR(st.wearRight))}${ring(st.batteryCase ?? -1, 'c', 'Кейс', st.chargingCase, '')}</div>
      </div>
      ${tops.length ? `<div class="card" style="margin-top:12px">
        <div class="card-title">Шумоподавление</div>
        <div class="anc" id="anc" style="grid-template-columns:repeat(${tops.length},1fr)"><span class="lens" style="width:calc((100% - 12px) / ${tops.length});transform:translateX(${Math.max(0, cur) * 100}%);opacity:${cur < 0 ? 0 : 1}"></span>
          ${tops.map((x, i) => `<button data-top="${i}" class="${i === cur ? 'on' : ''}">${icon[x.t]}${MT[x.t]}</button>`).join('')}
        </div>
        ${kind === 5 && levels.length ? `<div class="levels">${levels.map((l) => `<button class="filter ${st.ancMode === l.bit ? 'on' : ''}" data-level="${l.bit}">${MT[l.t]}</button>`).join('')}</div>` : ''}
        <div class="anc-desc">${desc}${smartTxt}</div>
      </div>` : ''}
      ${tiles.length ? `<div class="tiles" style="margin-top:12px">${tiles.map((t) => tile(...t)).join('')}</div>` : ''}
      ${hasCmd(0x0400) ? `<div class="card" style="margin-top:12px;display:flex;align-items:center;gap:14px">
        <span class="ic a" style="width:42px;height:42px;border-radius:14px;display:grid;place-items:center;flex:none">${I.bell}</span>
        <div style="flex:1"><div style="font-weight:650">Найти наушники</div><div class="note">Наушники громко запищат</div></div>
        <button class="btn sm ${S.finding ? 'danger find-btn on' : ''}" style="width:auto;padding:0 16px" data-find>${S.finding ? 'Стоп' : 'Пищать'}</button>
      </div>` : ''}
      ${!st.connected ? `<button class="btn primary" style="margin-top:14px" data-connect>${I.bt} Подключиться</button>` : ''}
      ${st.error ? `<div class="err" style="margin:12px 4px 0">${esc(st.error)}</div>` : ''}`;
  }

  const EQF = [62, 250, 1000, 4000, 8000, 16000];
  function vSound() {
    const st = S.st;
    const presets = eqPresets();
    const g = S.eq;
    const band = (i) => {
      const top = 50 - (g[i] / 6) * 42;
      return `<div class="band"><div class="db">${g[i] > 0 ? '+' : ''}${g[i]}</div>
        <div class="track" data-band="${i}"><div class="mid"></div><div class="fill" style="top:${g[i] >= 0 ? top : 50}%;bottom:${g[i] >= 0 ? 50 : 100 - top}%"></div><div class="knob" style="top:${top}%"></div></div>
        <div class="hz">${EQF[i] >= 1000 ? EQF[i] / 1000 + 'к' : EQF[i]}</div></div>`;
    };
    const audio = [
      hasFeat(27) || (model() && model().spatialSwitch) ? tile(27, I.spatial, 'g', 'Объёмный звук', '3D-эффект для музыки и кино') : '',
      hasFeat(24) ? tile(24, I.hires, 'v', 'Hi-Res звук', 'Высокое качество (LDAC/LHDC)') : '',
      hasFeat(29) ? tile(29, I.bass, 'c', 'Динамический бас', 'Усиление низких частот') : '',
    ].filter(Boolean);
    return `${topbar('Звук')}
      <div class="section" style="margin-top:4px">Звуковой профиль</div>
      <div class="eqp">${presets.map((p) => `<button class="card eqcard ${st.eqPreset === p.id ? 'active' : ''}" data-eqp="${p.id}">
          <div class="eqviz">${[...Array(7)].map((_, k) => `<i style="height:${18 + Math.abs(Math.sin((k + 1) * (p.id + 2))) * 26}px"></i>`).join('')}</div>
          <div class="t">${p.name}</div>${p.sub ? `<div class="s">${p.sub}</div>` : ''}</button>`).join('')}</div>
      ${audio.length ? `<div class="tiles" style="margin-top:12px">${audio.join('')}</div>
        ${hasFeat(24) && hasFeat(27) ? '<div class="note" style="margin:8px 6px 0">Hi-Res и объёмный звук обычно не работают одновременно. После переключения Hi-Res наушники на пару секунд переподключатся.</div>' : ''}` : ''}
      ${hasCmd(0x0418) ? `<div class="card" style="margin-top:12px"><div class="card-title">Свой эквалайзер <span style="text-transform:none;letter-spacing:0;color:${st.eqAcked ? 'var(--green)' : 'var(--dim)'}">${st.eqAcked ? 'принят' : 'экспериментально'}</span></div>
        <div class="eq" style="grid-template-columns:repeat(6,1fr)">${[0, 1, 2, 3, 4, 5].map(band).join('')}</div>
        <div class="btn-row"><button class="btn sm" data-eq-reset>Сбросить</button><button class="btn primary sm" data-eq-apply>Применить</button></div></div>` : ''}
      <div class="card" style="margin-top:12px"><div class="card-title">Звуки для сна <span style="text-transform:none;letter-spacing:0;color:var(--dim)">играет телефон</span></div>
        <div class="seg" id="noiseType">${[['white', 'Белый'], ['pink', 'Розовый'], ['brown', 'Коричневый'], ['rain', 'Дождь']].map(([k, n]) => `<button data-noise="${k}" class="${(S.noise && S.noise.type === k) ? 'on' : ''}">${n}</button>`).join('')}</div>
        <div class="seg" style="margin-top:8px">${[[15, '15 мин'], [30, '30 мин'], [60, '1 час'], [0, '∞']].map(([m, n]) => `<button data-timer="${m}" class="${(store.get('timer', 30) === m) ? 'on' : ''}">${n}</button>`).join('')}</div>
        <button class="btn ${S.noise ? 'danger' : 'primary'} sm" style="margin-top:12px" data-noise-toggle>${S.noise ? I.stop + ' Остановить' : I.play + ' Включить'}</button>
        ${S.noise && S.noise.until ? `<div class="note" style="margin-top:8px;text-align:center">Выключится в ${new Date(S.noise.until).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</div>` : ''}
      </div>`;
  }

  function vGest() {
    const g = S.st.gestures || {};
    const rows = gestureRows();
    const hc = holdCycleRow();
    const { tops } = ancTable();
    const hm = S.st.holdMask;
    return `${topbar('Жесты')}
      <div class="anc" style="grid-template-columns:1fr 1fr;margin-bottom:12px"><span class="lens" style="width:calc((100% - 12px) / 2);transform:translateX(${S.side === 1 ? 0 : 100}%)"></span>
        <button style="height:48px" class="${S.side === 1 ? 'on' : ''}" data-side="1">Левый</button><button style="height:48px" class="${S.side === 2 ? 'on' : ''}" data-side="2">Правый</button></div>
      <div class="card">${rows.map((r) => { const fn = g[`${S.side}:1:${r.act}`]; return `<div class="gest-row" data-gest="${r.act}"><span class="k">${r.title}</span><span class="v">${fn === undefined ? '—' : (FN_NAME[fn] || 'Код ' + fn)} ${I.chev}</span></div>`; }).join('')}</div>
      ${hc && tops.length > 1 ? `<div class="section">Что переключает удержание</div>
        <div class="card">${tops.map((x) => `<div class="gest-row" data-hold="${x.bit}"><span class="k">${MT[x.t]}</span>${sw(hm >= 0 && (hm & x.bit))}</div>`).join('')}
        <div class="note" style="margin-top:8px">Минимум ${hc.minSelectCount || 2} режима. Работает, если на удержание назначено «Режимы шумоподавления».</div></div>` : ''}
      <div class="note" style="margin:12px 6px">Если пусто — наушники ещё не прислали настройки. Нажми ⟳ вверху.</div>`;
  }

  function vMore() {
    const st = S.st;
    const m = model();
    const fitTxt = (r) => ({ good: '<span style="color:var(--green)">Отлично</span>', average: '<span style="color:var(--amber)">Нормально</span>', poor: '<span style="color:var(--red)">Плохо</span>', error: 'Ошибка' }[r] || '—');
    return `${topbar('Ещё')}
      ${hasCmd(0x0405) ? `<div class="card"><div class="card-title">Проверка посадки</div>
        <div class="note" style="margin-bottom:12px">Вставь оба наушника и не двигайся ~10 секунд. Наушники проиграют тестовый звук и проверят, подходят ли амбушюры.</div>
        <div class="grid" style="margin-bottom:12px"><div class="kv-glass card stat" style="padding:12px"><div class="k">Левый</div><div class="v" style="font-size:17px">${fitTxt(st.fitLeft)}</div></div>
          <div class="card stat" style="padding:12px"><div class="k">Правый</div><div class="v" style="font-size:17px">${fitTxt(st.fitRight)}</div></div></div>
        <button class="btn primary sm" data-fit>Начать проверку</button></div>` : ''}
      ${hasCmd(0x0427) ? `<div class="card"><div class="card-title">Громкость подсказок <span style="text-transform:none;letter-spacing:0">${st.alertVolume > 0 ? st.alertVolume : ''}</span></div>
        <input type="range" min="1" max="10" step="1" value="${st.alertVolume > 0 ? st.alertVolume : 5}" class="range" id="alertVol">
        <div class="note" style="margin-top:6px">Голосовые сигналы наушников: включение, заряд, смена режима.</div></div>` : ''}
      ${hasCmd(0x0112) ? `<div class="card"><div class="card-title">Подключённые устройства <button class="more-toggle" style="margin:0" data-devq>${I.refresh}</button></div>
        ${(st.devices || []).length ? st.devices.map((d) => `<div class="gest-row"><span class="k" style="display:flex;gap:10px;align-items:center"><span style="color:${d.connected ? 'var(--green)' : 'var(--dim)'}">${I.phone}</span>${esc(d.name || d.mac)}${d.thisPhone ? ' <span class="badge" style="background:rgba(139,92,246,.18);color:#c4b5fd">этот</span>' : ''}</span><span class="v" style="color:var(--muted);font-weight:500">${d.connected ? (d.playing ? 'играет' : 'подключено') : 'не подключено'}</span></div>`).join('') : '<div class="note">Список ещё не получен</div>'}</div>` : ''}
      <div class="card"><div class="card-title">Устройство</div><div class="rows">
        <div class="kv"><span>Модель</span><span>${esc((m && m.n) || st.name || '—')}</span></div>
        <div class="kv"><span>ID модели</span><span class="mono" style="font-size:12px">${esc(st.productId || '—')}</span></div>
        <div class="kv"><span>Прошивка</span><span>${esc(st.firmware || '—')}</span></div>
        <div class="kv"><span>Адрес</span><span class="mono" style="font-size:12px">${esc(st.address || '—')}</span></div>
      </div>
      <div class="btn-row"><button class="btn sm" data-pick>Сменить</button><button class="btn sm" data-disc>${st.connected ? 'Отключить' : 'Подключить'}</button></div></div>
      <div class="card"><div class="card-title">Виджет и шторка</div>
        <div class="note">Зажми пустое место на рабочем столе → «Виджеты» → <b>Buds Air</b>: заряд и кнопки режимов.<br><br>
        В шторке открой редактирование быстрых настроек и добавь плитку <b>«Наушники»</b> — нажатие переключает режим.</div></div>
      <div class="card"><div class="card-title">Для отладки</div>
        <div class="btn-row" style="margin-top:0"><button class="btn sm" data-log>Журнал</button><button class="btn sm" data-raw>Своя команда</button></div></div>
      <div class="note" style="text-align:center;margin:18px 10px">Buds Air 2.0 · Обновление прошивки и AI-функции realme Link работают через серверы realme и здесь недоступны.<br>Протокол — по открытым проектам QuickBuds, Gadgetbridge и buds-control (GPL-3.0).</div>`;
  }

  // ---------------------------------------------------------------- noise generator (звуки для сна)
  function startNoise(type) {
    stopNoise(true);
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const len = ctx.sampleRate * 4;
    const buf = ctx.createBuffer(2, len, ctx.sampleRate);
    for (let ch = 0; ch < 2; ch++) {
      const d = buf.getChannelData(ch);
      let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0, last = 0;
      for (let i = 0; i < len; i++) {
        const w = Math.random() * 2 - 1;
        if (type === 'pink' || type === 'rain') {
          b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.969 * b2 + w * 0.153852;
          b3 = 0.8665 * b3 + w * 0.3104856; b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898;
          d[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11; b6 = w * 0.115926;
        } else if (type === 'brown') { last = (last + 0.02 * w) / 1.02; d[i] = last * 3.5; }
        else d[i] = w * 0.35;
      }
    }
    const src = ctx.createBufferSource(); src.buffer = buf; src.loop = true;
    const gain = ctx.createGain(); gain.gain.value = 0;
    let node = src;
    if (type === 'rain') { const f = ctx.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = 900; src.connect(f); node = f; }
    node.connect(gain); gain.connect(ctx.destination);
    src.start(); gain.gain.linearRampToValueAtTime(0.6, ctx.currentTime + 2);
    const min = store.get('timer', 30);
    S.noise = { type, ctx, gain, until: min ? Date.now() + min * 60000 : 0 };
    if (min) S.noise.timer = setTimeout(() => stopNoise(), min * 60000);
  }
  function stopNoise(instant) {
    const n = S.noise; if (!n) return;
    clearTimeout(n.timer); S.noise = null;
    try { n.gain.gain.linearRampToValueAtTime(0, n.ctx.currentTime + (instant ? 0.05 : 3)); setTimeout(() => n.ctx.close(), instant ? 100 : 3200); } catch {}
    if ($('#view') && S.tab === 'sound') render();
  }

  // ---------------------------------------------------------------- events
  function bind(v) {
    v.onclick = async (e) => {
      const t = e.target.closest('[data-top],[data-level],[data-feat],[data-find],[data-connect],[data-refresh],[data-eqp],[data-eq-apply],[data-eq-reset],[data-side],[data-gest],[data-hold],[data-pick],[data-disc],[data-log],[data-raw],[data-fit],[data-devq],[data-noise],[data-timer],[data-noise-toggle]');
      if (!t) return;
      haptic();
      const d = t.dataset;
      try {
        if (d.top !== undefined) {
          const { tops, levels } = ancTable();
          const x = tops[+d.top];
          const lens = $('#anc .lens'); lens.style.opacity = 1; lens.style.transform = `translateX(${+d.top * 100}%)`; lens.classList.remove('squish'); void lens.offsetWidth; lens.classList.add('squish');
          $$('#anc button').forEach((b) => b.classList.toggle('on', b === t));
          let mask = x.bit;
          if (x.t === 5 && levels.length) mask = S.st.ancLevel && levels.some((l) => l.bit === S.st.ancLevel) ? S.st.ancLevel : x.bit;
          S.st.ancMode = mask;
          setTimeout(() => render(), 420);
          await Buds.setAnc({ mask });
        } else if (d.level) {
          S.st.ancMode = +d.level; S.st.ancLevel = +d.level; render();
          await Buds.setAnc({ mask: +d.level });
        } else if (d.feat) {
          const id = +d.feat, on = feat(id) !== 1;
          if (id === 24) toast('Наушники переподключатся на пару секунд');
          S.st.features = { ...(S.st.features || {}), [id]: on ? 1 : 0 }; render();
          await Buds.setFeature({ id, on });
        } else if (d.find !== undefined) {
          S.finding = !S.finding; render();
          await Buds.find({ start: S.finding });
          if (S.finding) setTimeout(() => { if (S.finding) { S.finding = false; Buds.find({ start: false }); render(); } }, 30000);
        } else if (d.connect !== undefined) {
          await connect();
        } else if (d.refresh !== undefined) {
          t.classList.add('spin');
          if (!S.st.connected) await connect(); else await Buds.refresh();
          setTimeout(() => t.classList.remove('spin'), 700);
        } else if (d.eqp !== undefined) {
          S.st.eqPreset = +d.eqp; render();
          await Buds.setEqPreset({ id: +d.eqp });
        } else if (d.eqApply !== undefined) {
          await Buds.setCustomEq({ freqs: EQF, gains: S.eq }); toast('Отправлено в наушники');
        } else if (d.eqReset !== undefined) {
          S.eq = [0, 0, 0, 0, 0, 0]; store.set('eq', S.eq); render();
        } else if (d.side) {
          S.side = +d.side; render();
        } else if (d.gest) {
          gestSheet(+d.gest);
        } else if (d.hold) {
          const bit = +d.hold; let m = S.st.holdMask >= 0 ? S.st.holdMask : 0;
          const next = m & bit ? m & ~bit : m | bit;
          const min = (holdCycleRow() || {}).minSelectCount || 2;
          if (next.toString(2).split('1').length - 1 < min) { toast(`Нужно минимум ${min} режима`, true); return; }
          S.st.holdMask = next; render();
          await Buds.setHoldMask({ mask: next });
        } else if (d.fit !== undefined) {
          await Buds.fitTest({ start: true }); toast('Проверка началась — не двигайся');
        } else if (d.devq !== undefined) {
          await Buds.queryDevices();
        } else if (d.noise) {
          if (S.noise) { startNoise(d.noise); render(); } else { $$('[data-noise]', v).forEach((b) => b.classList.toggle('on', b === t)); S.pendingNoise = d.noise; }
        } else if (d.timer !== undefined) {
          store.set('timer', +d.timer); if (S.noise) startNoise(S.noise.type); render();
        } else if (d.noiseToggle !== undefined) {
          if (S.noise) stopNoise(); else { startNoise(S.pendingNoise || 'pink'); render(); }
        } else if (d.pick !== undefined) {
          renderPicker();
        } else if (d.disc !== undefined) {
          if (S.st.connected) await Buds.disconnect(); else await connect();
        } else if (d.log !== undefined) {
          logSheet();
        } else if (d.raw !== undefined) {
          rawSheet();
        }
      } catch (err) { toast(err.message || String(err), true); }
    };
    const av = $('#alertVol', v);
    if (av) av.onchange = async () => { haptic(); try { await Buds.setAlertVolume({ level: +av.value }); } catch (e) { toast(e.message, true); } };
    $$('.track', v).forEach((tr) => {
      const set = (y) => {
        const r = tr.getBoundingClientRect();
        const p = Math.min(1, Math.max(0, (y - r.top) / r.height));
        const gval = Math.max(-6, Math.min(6, Math.round(((0.5 - p) / 0.42) * 6)));
        const i = +tr.dataset.band;
        if (S.eq[i] === gval) return;
        S.eq[i] = gval; store.set('eq', S.eq); haptic();
        const top = 50 - (gval / 6) * 42;
        $('.knob', tr).style.top = top + '%';
        const f = $('.fill', tr); f.style.top = (gval >= 0 ? top : 50) + '%'; f.style.bottom = (gval >= 0 ? 50 : 100 - top) + '%';
        tr.parentElement.querySelector('.db').textContent = (gval > 0 ? '+' : '') + gval;
      };
      tr.addEventListener('pointerdown', (e) => { tr.setPointerCapture(e.pointerId); set(e.clientY); });
      tr.addEventListener('pointermove', (e) => { if (e.buttons || e.pointerType === 'touch') set(e.clientY); });
    });
  }

  function gestSheet(act) {
    const row = gestureRows().find((r) => r.act === act);
    const cur = (S.st.gestures || {})[`${S.side}:1:${act}`];
    const opts = SUPPORT.filter(([bit]) => row.mask & bit);
    openSheet(`<h2>${row.title}</h2><div class="lead">${S.side === 1 ? 'Левый' : 'Правый'} наушник</div>
      <div class="pick">${opts.map(([, fn, n]) => `<button data-a="${fn}" class="${fn === cur ? 'on' : ''}"><div class="t">${n}</div><span class="radio"></span></button>`).join('')}</div>`, (sh, close) => {
      $$('[data-a]', sh).forEach((b) => b.onclick = async () => {
        haptic();
        try { await Buds.setGesture({ dev: S.side, btn: 1, act, fn: +b.dataset.a }); toast('Сохранено'); close(); } catch (e) { toast(e.message, true); }
      });
    });
  }

  async function logSheet() {
    const { lines } = await Buds.getLog();
    openSheet(`<h2>Журнал</h2><div class="lead">→ отправлено, ← получено от наушников</div>
      <div class="link-box mono" style="max-height:52vh;overflow:auto">${esc((lines || []).slice(-200).join('\n') || 'пусто')}</div>
      <button class="btn sm" style="margin-top:12px" id="lg">${I.copy} Скопировать</button>`, (sh) => {
      $('#lg', sh).onclick = async () => { try { await navigator.clipboard.writeText(lines.join('\n')); toast('Скопировано'); } catch { toast('Не удалось скопировать', true); } };
    });
  }

  function rawSheet() {
    openSheet(`<h2>Своя команда</h2><div class="lead">Для экспериментов: код команды и байты в hex</div>
      <div class="field"><label>Команда</label><input id="rc" value="010C" autocapitalize="characters"></div>
      <div class="field"><label>Данные</label><input id="rp" value="01 01" autocapitalize="characters"></div>
      <button class="btn primary sm" id="rs">Отправить</button><div class="note" style="margin-top:10px">Ответ смотри в журнале.</div>`, (sh) => {
      $('#rs', sh).onclick = async () => {
        try {
          const cmd = parseInt($('#rc', sh).value.replace(/\s/g, ''), 16);
          const payload = ($('#rp', sh).value.trim() ? $('#rp', sh).value.trim().split(/[\s,]+/) : []).map((x) => parseInt(x, 16) & 0xFF);
          await Buds.send({ cmd, payload }); toast('Отправлено');
        } catch (e) { toast(e.message, true); }
      };
    });
  }

  // ---------------------------------------------------------------- device picker
  async function renderPicker() {
    app.innerHTML = `<div class="screen login" style="justify-content:flex-start;padding-top:calc(var(--sat) + 40px)">
      <div class="orb sm">${I.head.replace('width="22" height="22"', 'width="40" height="40"').replace('stroke="currentColor"', 'stroke="#fff"')}</div>
      <h1>Твои <span>наушники</span></h1>
      <p class="lead">Выбери наушники из сопряжённых устройств. Сначала подключи их к телефону в настройках Bluetooth и закрой realme Link.</p>
      <div id="devs"><div class="sk" style="height:70px"></div></div>
      <button class="more-toggle" id="rescan" style="margin:16px auto 0">${I.refresh} Обновить список</button>
    </div>`;
    const load = async () => {
      const box = $('#devs');
      try {
        const perm = await Buds.ensurePermission();
        if (!perm.granted) { box.innerHTML = `<div class="err">Без разрешения «Устройства поблизости» приложение не видит наушники.</div><button class="btn primary" id="perm">Разрешить</button>`; $('#perm').onclick = load; return; }
        const { devices } = await Buds.getBonded();
        const score = (d) => (d.connected ? 0 : /realme|buds|oppo|oneplus|enco/i.test(d.name) ? 1 : 2);
        devices.sort((a, b) => score(a) - score(b) || a.name.localeCompare(b.name));
        box.innerHTML = devices.length ? devices.map((d) => `<button class="card dev ${score(d) < 2 ? 'star' : ''}" data-addr="${esc(d.address)}" data-name="${esc(d.name)}">
            <span class="ic">${I.head}</span><div style="flex:1;min-width:0"><div class="n">${esc(d.name)}</div><div class="a">${d.connected ? '<span style="color:var(--green)">● подключены к телефону</span>' : esc(d.address)}</div></div>${I.chev}</button>`).join('')
          : '<div class="empty">Нет сопряжённых устройств</div>';
        $$('[data-addr]', box).forEach((b) => b.onclick = async () => {
          haptic();
          store.set('address', b.dataset.addr); store.set('name', b.dataset.name);
          S.st = { ...S.st, address: b.dataset.addr, name: b.dataset.name };
          app.innerHTML = ''; S.tab = 'home'; render(true);
          connect();
        });
      } catch (e) { box.innerHTML = `<div class="err">${esc(e.message || e)}</div>`; }
    };
    $('#rescan').onclick = load;
    load();
  }

  async function connect() {
    const addr = S.st.address || store.get('address');
    if (!addr) return renderPicker();
    S.st.connecting = true; S.st.error = null; if ($('#view')) render();
    try {
      const perm = await Buds.ensurePermission();
      if (!perm.granted) throw new Error('Нужно разрешение «Устройства поблизости»');
      const st = await Buds.connect({ address: addr });
      merge(st);
      toast('Наушники подключены');
    } catch (e) { S.st.connecting = false; S.st.error = e.message || String(e); }
    if ($('#view')) render();
  }

  function merge(st) {
    S.st = { ...S.st, ...st };
    if (!S.st.name) S.st.name = store.get('name');
    if (!S.st.address) S.st.address = store.get('address');
    if (S.st.productId) store.set('product', S.st.productId);
  }

  // ---------------------------------------------------------------- boot
  async function boot() {
    try {
      const SB = native ? plugin('StatusBar') : null;
      if (SB) { SB.setStyle({ style: 'DARK' }).catch(() => {}); SB.setBackgroundColor({ color: '#05050b' }).catch(() => {}); }
      const App = native ? plugin('App') : null;
      if (App) {
        App.addListener('backButton', () => { if (overlayStack.length) overlayStack[overlayStack.length - 1](); else if (S.tab !== 'home') { S.tab = 'home'; render(true); } else App.exitApp(); });
        App.addListener('resume', () => { if (S.st.connected) Buds.refresh(); else autoFind(); });
      }
    } catch {}
    if (!Buds) { fatal('Не найден модуль Bluetooth приложения. Переустанови приложение.'); return; }
    let rt;
    try { Buds.addListener('state', (st) => {
      const prev = JSON.stringify(S.st); merge(st);
      if (S.finding && st.connected === false) S.finding = false;
      const busy = $('.sheet.show') || (document.activeElement && document.activeElement.tagName === 'INPUT');
      if ($('#view') && !busy && prev !== JSON.stringify(S.st)) { clearTimeout(rt); rt = setTimeout(() => render(), 60); }
    }); } catch (e) { console.error(e); }
    try { merge(await timeout(Buds.getState())); } catch (e) { console.error(e); }
    if (!S.st.address && !store.get('address')) {
      const found = await autoFind();
      if (!found) return renderPicker();
    }
    render(true);
    if (!S.st.connected && !S.st.connecting) autoFind();
    // пока приложение открыто — раз в 5 секунд проверяем, не подключились ли наушники к телефону
    setInterval(() => { if (!document.hidden && !S.st.connected && !S.st.connecting) autoFind(); }, 5000);
  }
  /** Ищет наушники, которые уже подключены к телефону, и сам подключается к ним. */
  let finding = false;
  async function autoFind() {
    if (finding || !Buds || !Buds.autoConnect) return null;
    finding = true;
    try {
      const { address } = await timeout(Buds.autoConnect(), 4000);
      if (address) {
        if (!store.get('address')) { store.set('address', address); S.st.address = address; }
        if (!$('.nav')) { S.tab = 'home'; app.innerHTML = ''; render(true); }
      }
      return address || null;
    } catch { return null; } finally { finding = false; }
  }

  function fatal(msg) {
    app.innerHTML = `<div class="screen login" style="justify-content:center;padding:24px"><div class="err" style="white-space:pre-wrap">${esc(msg)}</div>
      <button class="btn primary" style="margin-top:14px" onclick="location.reload()">Перезапустить</button></div>`;
  }
  window.addEventListener('error', (e) => { if (!app.children.length) fatal('Ошибка: ' + (e.message || e)); });
  window.addEventListener('unhandledrejection', (e) => console.error(e.reason));
  boot().catch((e) => fatal('Ошибка запуска: ' + (e && e.message || e)));
})();
