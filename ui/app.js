// Blockmate 聊天网页（手册 5.6，细则第 14 节）。纯浏览器脚本，由后台直接提供。
(function () {
  'use strict';

  const TOKEN_KEY = 'companion_token';
  const BACKOFF = [1, 2, 4, 8, 15]; // 秒

  const $ = (id) => document.getElementById(id);
  const el = {
    conn: $('conn'), online: $('online'), task: $('task'), health: $('health'), dim: $('dim'), inv: $('inv'),
    model: $('model'), keeper: $('keeper'), keeperStart: $('keeper-start'), keeperStop: $('keeper-stop'),
    stop: $('stop'), chat: $('chat'), prefs: $('prefs'), prefsList: $('prefs-list'), prefsCount: $('prefs-count'),
    composer: $('composer'), input: $('input'), send: $('send'),
    tokenDialog: $('token-dialog'), tokenForm: $('token-form'), tokenInput: $('token-input'),
  };

  let ws = null;
  let token = localStorage.getItem(TOKEN_KEY) || '';
  let attempt = 0;
  let reconnectTimer = null;
  let closedByUs = false;
  const replies = new Map(); // reply_id -> bubble element

  // ---------- 小工具 ----------
  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }
  function envelope(type, body) {
    return { v: 1, type, id: uuid(), ts: Date.now(), from: 'ui', to: 'hub', body: body || {} };
  }
  function send(type, body) {
    if (!ws || ws.readyState !== WebSocket.OPEN) { system('还没连上后台，稍等再试'); return false; }
    ws.send(JSON.stringify(envelope(type, body)));
    return true;
  }
  function pill(node, text, cls) {
    node.textContent = text;
    node.className = 'pill' + (cls ? ' ' + cls : '');
  }
  function timeText(ts) {
    const d = ts ? new Date(ts) : new Date();
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }
  function scrollDown() { el.chat.scrollTop = el.chat.scrollHeight; }

  // ---------- 聊天记录 ----------
  function bubble(role, text, ts) {
    const div = document.createElement('div');
    div.className = 'msg msg-' + role;
    const body = document.createElement('span');
    body.className = 'body';
    body.textContent = text || '';
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = (role === 'owner' ? '我' : role === 'companion' ? 'Blockmate' : '') + ' ' + timeText(ts);
    div.appendChild(body);
    div.appendChild(meta);
    el.chat.appendChild(div);
    scrollDown();
    return div;
  }
  function system(text) {
    const div = document.createElement('div');
    div.className = 'msg msg-system';
    div.textContent = text;
    el.chat.appendChild(div);
    scrollDown();
  }
  function renderHistory(history) {
    el.chat.innerHTML = '';
    (history || []).forEach((m) => {
      if (!m.text) return;
      const role = m.role === 'owner' || m.role === 'owner_ingame' ? 'owner' : m.role === 'companion' ? 'companion' : 'system';
      if (role === 'system') return;
      const b = bubble(role, m.text, m.ts);
      if (m.role === 'owner_ingame') b.querySelector('.meta').textContent = '我（游戏里）' + ' ' + timeText(m.ts);
    });
  }

  // ---------- 状态条 ----------
  const TASK_NAMES = {
    follow: '跟随中', come_here: '正在过去', wait_here: '原地等待', stop: '正在停下', pick_up: '正在捡东西',
    deliver: '正在交付', report_status: '查看状态', cancel: '取消中', rejoin: '正在重连',
  };
  const DIM_NAMES = { 'minecraft:overworld': '主世界', 'minecraft:the_nether': '下界', 'minecraft:the_end': '末地' };
  function renderStatus(s) {
    if (!s) return;
    const c = s.companion || {};
    if (!c.online) pill(el.online, '离线', 'pill-bad');
    else if (c.in_world === false) pill(el.online, '客户端在，未进服', 'pill-warn');
    else pill(el.online, '在线', 'pill-ok');
    if (s.task && s.task.name) pill(el.task, (TASK_NAMES[s.task.name] || s.task.name) + (s.task.state === 'pending' ? '（等回应）' : ''), 'pill-warn');
    else pill(el.task, '无任务', '');
    pill(el.health, '♥ ' + (c.health == null ? '--' : Math.round(c.health) + '/20'), c.health != null && c.health <= 6 ? 'pill-bad' : '');
    pill(el.dim, c.dim ? (DIM_NAMES[c.dim] || c.dim) : '--', '');
    if (c.inventory_summary) {
      const inv = c.inventory_summary.length > 40 ? c.inventory_summary.slice(0, 40) + '…' : c.inventory_summary;
      pill(el.inv, '背包：' + inv, ''); el.inv.hidden = false; el.inv.title = '背包：' + c.inventory_summary;
    } else { el.inv.hidden = true; }
    const m = s.model || {};
    pill(el.model, m.ok ? '模型正常' : '模型无响应', m.ok ? 'pill-ok' : 'pill-bad');
    const k = s.keeper || {};
    if (!k.connected) {
      pill(el.keeper, 'keeper 未连', '');
      el.keeperStart.disabled = true; el.keeperStop.disabled = true;
    } else {
      let text = k.running ? '游戏在跑' : '游戏未跑';
      if (k.desired) text += '，期望' + (k.desired === 'running' ? '在线' : '下线');
      if (k.gave_up) text += '，已放弃拉起';
      pill(el.keeper, text, k.gave_up ? 'pill-bad' : k.running ? 'pill-ok' : '');
      el.keeperStart.disabled = false; el.keeperStop.disabled = false;
    }
  }

  // ---------- 偏好 ----------
  function renderPrefs(items) {
    el.prefsList.innerHTML = '';
    const list = items || [];
    el.prefsCount.textContent = String(list.length);
    el.prefs.hidden = list.length === 0;
    if (list.length) el.prefs.open = true;
    list.forEach((p) => {
      const li = document.createElement('li');
      const text = document.createElement('span');
      text.textContent = p.text;
      const actions = document.createElement('span');
      actions.className = 'pref-actions';
      const ok = document.createElement('button');
      ok.className = 'btn btn-small'; ok.type = 'button'; ok.textContent = '确认';
      ok.onclick = () => { send('preference_action', { id: p.id, action: 'confirm' }); li.remove(); };
      const no = document.createElement('button');
      no.className = 'btn btn-small'; no.type = 'button'; no.textContent = '忽略';
      no.onclick = () => { send('preference_action', { id: p.id, action: 'ignore' }); li.remove(); };
      actions.appendChild(ok); actions.appendChild(no);
      li.appendChild(text); li.appendChild(actions);
      el.prefsList.appendChild(li);
    });
  }

  // ---------- 收消息 ----------
  function handle(env) {
    const b = env.body || {};
    switch (env.type) {
      case 'hello':
        if (b.ok) {
          renderStatus(b.status);
          renderHistory(b.history);
          system('已连上');
        } else {
          system('后台拒绝了连接：' + (b.error || '口令不对'));
        }
        break;
      case 'reply': {
        let node = replies.get(b.reply_id);
        if (!node) {
          node = bubble('companion', '', env.ts);
          node.classList.add('msg-pending');
          replies.set(b.reply_id, node);
        }
        if (b.chunk) node.querySelector('.body').textContent += b.chunk;
        if (b.done) {
          node.classList.remove('msg-pending');
          replies.delete(b.reply_id);
          if (!node.querySelector('.body').textContent.trim()) node.remove(); // 只下了命令没说话
        }
        scrollDown();
        break;
      }
      case 'status': renderStatus(b); break;
      case 'notice': system(b.text || ''); break;
      case 'preferences': renderPrefs(b.items); break;
      default: break;
    }
  }

  // ---------- 连接 ----------
  function wsUrl() {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    return proto + location.host + '/ws/ui?token=' + encodeURIComponent(token);
  }
  function connect() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (!token) { askToken(); return; }
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return; // 同一页面只开一个连接
    pill(el.conn, attempt ? '重连中' : '连接中', 'pill-warn');
    closedByUs = false;
    try {
      ws = new WebSocket(wsUrl());
    } catch (e) {
      scheduleReconnect();
      return;
    }
    ws.onopen = () => {
      attempt = 0;
      pill(el.conn, '已连接', 'pill-ok');
      ws.send(JSON.stringify(envelope('hello', { token, role: 'ui', client: 'web' })));
    };
    ws.onmessage = (ev) => {
      let env;
      try { env = JSON.parse(ev.data); } catch (e) { return; }
      handle(env);
    };
    ws.onclose = (ev) => {
      ws = null;
      if (ev.code === 4401) {
        pill(el.conn, '口令不对', 'pill-bad');
        localStorage.removeItem(TOKEN_KEY);
        token = '';
        askToken();
        return;
      }
      if (closedByUs) return;
      pill(el.online, '离线', 'pill-bad');
      scheduleReconnect();
    };
    ws.onerror = () => { /* onclose 会跟着来 */ };
  }
  function scheduleReconnect() {
    const sec = BACKOFF[Math.min(attempt, BACKOFF.length - 1)];
    attempt += 1;
    pill(el.conn, '重连中（' + sec + ' 秒后）', 'pill-warn');
    reconnectTimer = setTimeout(connect, sec * 1000);
  }
  function askToken() {
    if (typeof el.tokenDialog.showModal === 'function') {
      el.tokenInput.value = '';
      if (!el.tokenDialog.open) el.tokenDialog.showModal();
    } else {
      const t = window.prompt('输入后台 config.yaml 里的 token');
      if (t) { token = t.trim(); localStorage.setItem(TOKEN_KEY, token); connect(); }
    }
  }
  el.tokenForm.addEventListener('submit', () => {
    const t = el.tokenInput.value.trim();
    if (!t) return;
    token = t;
    localStorage.setItem(TOKEN_KEY, token);
    attempt = 0;
    setTimeout(connect, 0);
  });

  // ---------- 发消息 ----------
  el.composer.addEventListener('submit', (ev) => {
    ev.preventDefault();
    const text = el.input.value.trim();
    if (!text) return;
    if (send('chat', { text })) {
      bubble('owner', text);
      el.input.value = '';
    }
  });
  el.stop.addEventListener('click', () => {
    if (send('stop', {})) system('已发出：停');
  });
  el.keeperStart.addEventListener('click', () => { if (send('keeper_control', { action: 'start' })) system('已发出：叫它上线'); });
  el.keeperStop.addEventListener('click', () => { if (send('keeper_control', { action: 'stop' })) system('已发出：让它下线'); });

  window.addEventListener('beforeunload', () => { closedByUs = true; if (ws) ws.close(); });

  connect();
})();
