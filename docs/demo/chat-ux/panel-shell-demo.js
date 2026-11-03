(() => {
  'use strict';

  const WIDTH_KEY = 'ai-panel-demo:width:v3';
  const PANEL_MIN = 360;
  const PANEL_MAX = 640;
  const BUSINESS_MIN = 720;
  const DEFAULT_MIN = 360;
  const DEFAULT_MAX = 420;
  const SUPPORTS_FIELD_SIZING = Boolean(
    window.CSS && CSS.supports && CSS.supports('field-sizing', 'content')
  );

  const panel = document.getElementById('ai-panel');
  const workbench = document.getElementById('workbench');
  const messagesEl = document.getElementById('messages');
  const input = document.getElementById('composer-input');
  const sendBtn = document.getElementById('send-btn');
  const stopBtn = document.getElementById('stop-btn');
  const newChatBtn = document.getElementById('new-chat');
  const collapseBtn = document.getElementById('collapse-btn');
  const expandBtn = document.getElementById('expand-btn');
  const handle = document.getElementById('resize-handle');
  const stageScene = document.getElementById('stage-scene');
  const stageHint = document.getElementById('stage-hint');
  const demoStatus = document.getElementById('demo-status');
  const resizeStatus = document.getElementById('resize-status');
  const dialog = document.getElementById('new-chat-dialog');
  const dialogNote = document.getElementById('new-chat-note');
  const sceneButtons = Array.from(document.querySelectorAll('.scene-btn'));
  const widthValues = Array.from(document.querySelectorAll('[data-width-value]'));
  const widthMaxes = Array.from(document.querySelectorAll('[data-width-max]'));

  const SCENE_LABELS = {
    done: '普通完成',
    running: '运行中',
    waiting: '等待确认',
    empty: '空会话',
    custom: '自由对话',
  };

  const RUNNING_TEXT =
    '我正在读取最近一次导入的结果。\n\n' +
    '目前已读到 3 条失败记录：订单类型为空 1 条、生效日期格式不正确 1 条、配置编码重复 1 条。\n\n' +
    '接下来会按失败原因分组，并附上对应的行号和建议修正方式。整理完成后我会直接给出清单。';

  let seq = 0;
  const nextId = (prefix = 'msg') => `${prefix}-${++seq}`;

  const state = {
    scene: 'done',
    messages: [],
    running: false,
    waiting: false,
    collapsed: false,
    width: readStoredWidth(),
    applied: 0,
    resizing: false,
    dragStartX: 0,
    dragStartWidth: 0,
    runToken: 0,
    timer: null,
  };

  let announceTimer = null;
  let resizeRaf = 0;

  function readStoredWidth() {
    try {
      const raw = window.localStorage.getItem(WIDTH_KEY);
      if (raw === null) return null;
      const value = Number.parseInt(raw, 10);
      if (Number.isFinite(value)) {
        return Math.min(PANEL_MAX, Math.max(PANEL_MIN, value));
      }
    } catch (error) {
      /* localStorage may be unavailable; fall back to the viewport default. */
    }
    return null;
  }

  function saveStoredWidth(value) {
    try {
      window.localStorage.setItem(WIDTH_KEY, String(value));
    } catch (error) {
      /* Best effort only. */
    }
  }

  function defaultWidth() {
    return Math.min(DEFAULT_MAX, Math.max(DEFAULT_MIN, Math.round(window.innerWidth * 0.3)));
  }

  function preferredWidth() {
    return state.width ?? defaultWidth();
  }

  function maxDockWidth() {
    return Math.max(PANEL_MIN, Math.min(PANEL_MAX, Math.round(window.innerWidth) - BUSINESS_MIN));
  }

  function dockWidth(value) {
    return Math.min(maxDockWidth(), Math.max(PANEL_MIN, Math.round(value)));
  }

  function clockTime() {
    const now = new Date();
    return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function announce(text) {
    window.clearTimeout(announceTimer);
    demoStatus.textContent = '';
    announceTimer = window.setTimeout(() => {
      demoStatus.textContent = text;
    }, 30);
  }

  function makeUser(text, time) {
    return {
      id: nextId(),
      role: 'user',
      text,
      time: time || clockTime(),
      streaming: false,
      stopped: false,
      card: null,
      copyText: text,
    };
  }

  function makeAssistant(text, time, extra = {}) {
    return Object.assign(
      {
        id: nextId(),
        role: 'assistant',
        text,
        time: time || clockTime(),
        streaming: false,
        stopped: false,
        card: null,
        copyText: text,
      },
      extra
    );
  }

  function makeCard(rows) {
    return {
      id: nextId('card'),
      status: 'pending',
      business: '重新启动导出作业',
      rows,
    };
  }

  const SCENES = {
    done: () => [
      makeUser('帮我看下「订单配置」这个任务为什么一直排队，已经等了快十分钟了。', '14:02'),
      makeAssistant(
        '我查了任务中心，任务 #411「订单配置 · 全量导出」当前排在队列第 3 位。\n\n' +
          '前面两个作业都在等待导出资源释放，不是任务本身失败。按当前队列速度，预计 2 分钟左右会轮到你。\n\n' +
          '需要我盯着它，轮到时提醒你吗？',
        '14:02'
      ),
      makeUser('先不用盯，把这次导出的范围改成 DOC_TYPE 就行。', '14:04'),
      makeAssistant(
        '好的，已经按你的要求把范围改成 DOC_TYPE，并重新启动了任务 #411。\n\n' +
          '当前排在队列第 3 位，预计 2 分钟左右开始；完成后会在任务中心提示。',
        '14:04'
      ),
    ],
    running: () => [
      makeUser('最近一次导入跑完了吗？', '14:06'),
      makeAssistant(
        '跑完了。共导入 12,480 行，成功 12,477 行，失败 3 行。\n\n需要我把失败行展开吗？',
        '14:06'
      ),
      makeUser('要，整理成清单，按失败原因分组。', '14:07'),
      makeAssistant('', '14:07', { streaming: true, copyText: '' }),
    ],
    waiting: () => [
      makeUser('把任务 #411 的导出范围改成 DOC_TYPE，然后重新跑一次。', '14:09'),
      makeAssistant(
        '可以。这次会覆盖任务 #411 现有的导出参数，并重新启动一次全量导出。\n\n请确认下面的操作，我再执行。',
        '14:09',
        {
          card: makeCard([
            { key: '任务', value: '#411 · 订单配置 · 全量导出' },
            { key: '范围', value: 'DOC_TYPE' },
            { key: '影响', value: '覆盖现有导出参数并重新排队' },
          ]),
        }
      ),
    ],
    empty: () => [],
  };

  function renderRichText(container, text, options = {}) {
    container.textContent = '';
    const blocks = String(text || '')
      .split(/\n{2,}/)
      .filter((block) => block.length > 0);

    if (blocks.length === 0) {
      const paragraph = document.createElement('p');
      if (options.caret) paragraph.append(makeCaret());
      container.append(paragraph);
      return;
    }

    for (const block of blocks) {
      const lines = block.split('\n');
      const isList = lines.length > 0 && lines.every((line) => line.startsWith('• '));

      if (isList) {
        const list = document.createElement('ul');
        for (const line of lines) {
          list.append(el('li', '', line.slice(2)));
        }
        container.append(list);
      } else {
        const paragraph = document.createElement('p');
        lines.forEach((line, index) => {
          if (index > 0) paragraph.append(document.createElement('br'));
          paragraph.append(document.createTextNode(line));
        });
        container.append(paragraph);
      }
    }

    if (options.caret) {
      const last = container.lastElementChild;
      const target = last && last.tagName === 'UL' ? last.lastElementChild : last;
      (target || container).append(makeCaret());
    }
  }

  function makeCaret() {
    const caret = el('span', 'stream-caret');
    caret.setAttribute('aria-hidden', 'true');
    return caret;
  }

  function makeBreathing(label, waiting) {
    const wrap = el('span', waiting ? 'breathing waiting' : 'breathing');
    const dot = el('span', 'breathing-dot');
    dot.setAttribute('aria-hidden', 'true');
    const text = el('span', 'breathing-text', label);
    wrap.append(dot, text);
    return wrap;
  }

  function buildMeta(message) {
    const meta = el('div', message.role === 'user' ? 'meta-row user' : 'meta-row');
    const status = el('span', 'meta-status');

    if (message.streaming) {
      status.append(makeBreathing('工作中…', false));
    } else if (message.card && message.card.status === 'pending') {
      status.append(makeBreathing('等待中…', true));
    } else if (message.stopped) {
      status.append(el('span', 'stopped-badge', '已停止'));
      status.append(el('span', 'timestamp', message.time));
    } else {
      status.append(el('span', 'timestamp', message.time));
    }

    meta.append(status);

    if (
      message.role === 'assistant' &&
      !message.streaming &&
      message.text &&
      (!message.card || message.card.status !== 'pending')
    ) {
      const actions = el('span', 'meta-actions');
      const copy = el('button', 'meta-btn', '复制');
      copy.type = 'button';
      copy.dataset.copyId = message.id;
      copy.setAttribute('aria-label', '复制这条回答');
      actions.append(copy);
      meta.append(actions);
    }

    return meta;
  }

  function buildCard(card) {
    const wrap = el('div', 'hitl-card');
    wrap.setAttribute('role', 'group');
    wrap.setAttribute(
      'aria-label',
      card.status === 'approved'
        ? '已批准的操作'
        : card.status === 'rejected'
          ? '已拒绝的操作'
          : '待确认操作'
    );

    const head = el('div', 'hitl-head');
    const mark = el('span', 'hitl-mark');
    mark.setAttribute('aria-hidden', 'true');
    mark.textContent = card.status === 'approved' ? '✓' : card.status === 'rejected' ? '✗' : '?';
    if (card.status === 'approved') mark.classList.add('approved');
    if (card.status === 'rejected') mark.classList.add('rejected');

    const title = el(
      'div',
      'hitl-title',
      card.status === 'pending' ? `待你确认：${card.business}` : card.business
    );
    const chip = el('span', 'hitl-chip');
    if (card.status === 'approved') {
      chip.classList.add('approved');
      chip.textContent = '✓ 已批准';
    } else if (card.status === 'rejected') {
      chip.classList.add('rejected');
      chip.textContent = '✗ 已拒绝';
    } else {
      chip.textContent = '待确认';
    }

    head.append(mark, title, chip);

    const rows = el('div', 'hitl-rows');
    for (const row of card.rows) {
      const line = el('div', 'hitl-row');
      line.append(el('span', 'hitl-key', row.key));
      line.append(el('span', 'hitl-val', row.value));
      rows.append(line);
    }

    wrap.append(head, rows);

    if (card.status === 'pending') {
      const actions = el('div', 'hitl-actions');
      const reject = el('button', 'btn-sm btn-reject', '拒绝');
      reject.type = 'button';
      reject.dataset.cardAction = 'rejected';
      reject.dataset.cardId = card.id;
      const approve = el('button', 'btn-sm btn-approve', '批准');
      approve.type = 'button';
      approve.dataset.cardAction = 'approved';
      approve.dataset.cardId = card.id;
      actions.append(reject, approve);
      wrap.append(actions);
    }

    return wrap;
  }

  function buildMessage(message) {
    const row = el('article', `msg-row ${message.role}`);
    row.dataset.msgId = message.id;
    const col = el('div', `msg-col ${message.role}`);

    if (message.role === 'user') {
      col.append(el('div', 'user-bubble', message.text));
    } else {
      const body = el('div', 'assistant-body');
      body.dataset.role = 'body';
      renderRichText(body, message.text, { caret: message.streaming });
      col.append(body);
      if (message.card) col.append(buildCard(message.card));
    }

    col.append(buildMeta(message));
    row.append(col);
    return row;
  }

  function buildEmptyState() {
    const wrap = el('div', 'empty-state');
    wrap.append(el('div', 'empty-eyebrow', '配置助手'));
    wrap.append(el('div', 'empty-title', '您好，我可以帮你处理配置任务'));
    wrap.append(
      el(
        'p',
        'empty-desc',
        '查任务状态、整理导入失败行，或调整导出范围。涉及写操作时，我会先给出确认，再执行。'
      )
    );

    const chips = el('div', 'empty-chips');
    const suggestions = [
      ['查看任务 #411 的状态', '查看任务 #411 的状态'],
      ['整理最近一次导入的失败行', '整理最近一次导入的失败行'],
      ['把导出范围改成 DOC_TYPE', '把导出范围改成 DOC_TYPE'],
    ];
    for (const [label, text] of suggestions) {
      const button = el('button', 'chip-btn', label);
      button.type = 'button';
      button.dataset.suggest = text;
      chips.append(button);
    }
    wrap.append(chips);
    wrap.append(el('p', 'empty-note', '从上面任意一句开始，或者直接输入你的问题。'));
    return wrap;
  }

  function isNearBottom() {
    return messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 80;
  }

  function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function renderMessages(options = {}) {
    const stick = options.forceBottom || isNearBottom();
    const previousTop = messagesEl.scrollTop;
    messagesEl.textContent = '';

    if (state.messages.length === 0) {
      messagesEl.append(buildEmptyState());
    } else {
      for (const message of state.messages) {
        messagesEl.append(buildMessage(message));
      }
    }

    messagesEl.setAttribute('aria-busy', String(state.running));
    if (stick) scrollToBottom();
    else messagesEl.scrollTop = previousTop;
  }

  function updateStreamingDom(message) {
    const stick = isNearBottom();
    const row = messagesEl.querySelector(`[data-msg-id="${message.id}"]`);
    if (!row) {
      renderMessages();
      return;
    }
    const body = row.querySelector('[data-role="body"]');
    if (!body) return;
    renderRichText(body, message.text, { caret: true });
    if (stick) scrollToBottom();
  }

  function clearTimer() {
    if (state.timer !== null) {
      window.clearTimeout(state.timer);
      state.timer = null;
    }
  }

  function streamInto(message, fullText, options = {}) {
    const token = options.token ?? state.runToken;
    const holdAt = options.holdAt ?? 1;
    const limit = Math.max(1, Math.floor(fullText.length * holdAt));
    let cursor = 0;

    message.text = '';
    message.copyText = '';
    message.streaming = true;

    const tick = () => {
      if (token !== state.runToken) return;
      cursor = Math.min(limit, cursor + 5 + Math.floor(Math.random() * 9));
      message.text = fullText.slice(0, cursor);
      message.copyText = message.text;
      updateStreamingDom(message);

      if (cursor < limit) {
        state.timer = window.setTimeout(tick, 55);
      } else if (limit < fullText.length) {
        state.timer = null;
      } else {
        finishStream(message, fullText, options);
      }
    };

    state.timer = window.setTimeout(tick, 120);
  }

  function refreshMessageDom(message) {
    const stick = isNearBottom();
    const row = messagesEl.querySelector(`[data-msg-id="${message.id}"]`);
    if (!row) {
      renderMessages();
      return;
    }
    const body = row.querySelector('[data-role="body"]');
    if (body) renderRichText(body, message.text, { caret: message.streaming });
    const oldMeta = row.querySelector('.meta-row');
    if (oldMeta) oldMeta.replaceWith(buildMeta(message));
    if (stick) scrollToBottom();
  }

  function finishStream(message, fullText, options) {
    state.timer = null;
    message.text = fullText;
    message.copyText = fullText;
    message.streaming = false;
    message.time = clockTime();
    state.running = false;
    refreshMessageDom(message);
    messagesEl.setAttribute('aria-busy', 'false');
    updateComposer();
    announce('回答已完成');
    if (typeof options.onDone === 'function') options.onDone();
  }

  function startRunningScene() {
    const message = [...state.messages].reverse().find((item) => item.streaming);
    if (!message) return;
    streamInto(message, RUNNING_TEXT, { token: state.runToken, holdAt: 0.62 });
  }

  function loadScene(name) {
    clearTimer();
    state.runToken += 1;
    state.scene = name;
    state.messages = SCENES[name]();
    state.running = name === 'running';
    state.waiting = name === 'waiting';
    state.collapsed = false;
    panel.dataset.collapsed = 'false';
    input.value = '';
    applyWidth(preferredWidth());
    syncComposerHeight();
    syncSceneUI();
    renderMessages({ forceBottom: true });
    updateComposer();
    if (name === 'running') startRunningScene();
    announce(`已切换到${SCENE_LABELS[name]}场景`);
  }

  function syncSceneUI() {
    stageScene.textContent = SCENE_LABELS[state.scene] || SCENE_LABELS.custom;
    for (const button of sceneButtons) {
      button.setAttribute('aria-pressed', String(button.dataset.scene === state.scene));
    }
  }

  function updateComposer() {
    const hasText = input.value.trim().length > 0;
    const busy = state.running || state.waiting;

    sendBtn.disabled = busy || !hasText;
    sendBtn.hidden = state.running;
    stopBtn.hidden = !state.running;

    if (state.running) {
      input.placeholder = '正在生成，可先输入；结束后再发送';
    } else if (state.waiting) {
      input.placeholder = '可先输入；处理上方待办后继续';
    } else {
      input.placeholder = '输入消息，Enter 发送，Shift+Enter 换行';
    }

    messagesEl.setAttribute('aria-busy', String(state.running));
    syncComposerHeight();
  }

  function syncComposerHeight() {
    if (SUPPORTS_FIELD_SIZING) return;
    input.style.blockSize = 'auto';
    const next = Math.min(Math.max(input.scrollHeight, 40), 101);
    input.style.blockSize = `${next}px`;
  }

  function replyFor(text) {
    if (/失败|导入/.test(text)) {
      return (
        '我看了最近一次导入的结果：共 12,480 行，失败 3 行。\n\n' +
        '• 第 128 行：订单类型为空\n' +
        '• 第 214 行：生效日期格式不正确\n' +
        '• 第 307 行：配置编码重复\n\n' +
        '需要我把这三行整理成可下载的清单吗？'
      );
    }
    if (/导出|DOC_TYPE|配置/.test(text)) {
      return (
        '可以。任务 #411 当前使用的是「全部配置定义」范围。\n\n' +
        '如果改成 DOC_TYPE，我会先覆盖导出参数，再重新排队；执行前会先让你确认一次。'
      );
    }
    if (/任务|排队|状态/.test(text)) {
      return (
        '我查了一下任务中心：任务 #411「订单配置 · 全量导出」当前排在队列第 3 位，前面两个作业都在等待导出资源释放。\n\n' +
        '预计 2 分钟左右会轮到你，需要我在轮到时提醒你吗？'
      );
    }
    return (
      '收到。我基于当前页面的上下文看了一下：任务中心暂时没有阻断性异常，最近一次操作是任务 #411 的导出配置调整。\n\n' +
      '你可以继续问某条任务的状态，或让我调整导出范围。'
    );
  }

  function sendUserText(raw) {
    const text = String(raw ?? '').trim();
    if (!text || state.running || state.waiting) return;

    state.scene = 'custom';
    syncSceneUI();

    const time = clockTime();
    const user = makeUser(text, time);
    const reply = makeAssistant('', time, { streaming: true, copyText: '' });
    state.messages.push(user, reply);
    state.running = true;
    const token = ++state.runToken;

    input.value = '';
    renderMessages({ forceBottom: true });
    updateComposer();
    announce('已发送消息，配置助手正在生成回答');
    streamInto(reply, replyFor(text), { token });
    input.focus();
  }

  function resolveCard(cardId, decision) {
    if (!state.waiting) return;
    if (decision !== 'approved' && decision !== 'rejected') return;

    const source = state.messages.find(
      (item) => item.card && item.card.id === cardId && item.card.status === 'pending'
    );
    if (!source) return;

    source.card.status = decision;
    state.waiting = false;
    state.scene = 'custom';
    syncSceneUI();

    const time = clockTime();
    const reply = makeAssistant('', time, { streaming: true, copyText: '' });
    state.messages.push(reply);
    state.running = true;
    const token = ++state.runToken;

    renderMessages({ forceBottom: true });
    updateComposer();
    announce(decision === 'approved' ? '已批准，正在提交' : '已拒绝，未执行操作');

    const fullText =
      decision === 'approved'
        ? '已提交。任务 #411 已重新排队，范围是 DOC_TYPE；完成后会在任务中心提示。'
        : '好的，没有执行任何操作。任务 #411 的参数保持不变。';

    streamInto(reply, fullText, { token });
    input.focus();
  }

  function stopRun() {
    if (!state.running) return;
    state.runToken += 1;
    clearTimer();

    const message = [...state.messages]
      .reverse()
      .find((item) => item.role === 'assistant' && item.streaming);
    if (message) {
      message.streaming = false;
      message.stopped = true;
      if (!message.text) message.text = '已停止生成。';
      message.copyText = message.text;
    }

    state.running = false;
    state.scene = 'custom';
    syncSceneUI();
    renderMessages();
    updateComposer();
    announce('已停止生成');
    input.focus();
  }

  function startEmptyConversation(alreadyEmpty) {
    state.runToken += 1;
    clearTimer();
    state.messages = [];
    state.running = false;
    state.waiting = false;
    state.scene = 'empty';
    input.value = '';
    syncSceneUI();
    renderMessages({ forceBottom: true });
    updateComposer();
    announce(alreadyEmpty ? '已新建对话，当前为空会话' : '当前对话已清空，已新建对话');
  }

  function requestNewChat() {
    const hasContent = state.messages.length > 0;
    const active = state.running || state.waiting;

    if (!hasContent && !active) {
      startEmptyConversation(true);
      return;
    }

    if (active) {
      dialogNote.hidden = false;
      dialogNote.textContent = state.waiting
        ? '当前对话还有待确认的操作，清空后这些内容将不会保留。'
        : '当前回答仍在生成，清空后本轮内容将不会保留。';
    } else {
      dialogNote.hidden = true;
      dialogNote.textContent = '';
    }

    dialog.showModal();
  }

  function syncWidthUI() {
    const max = maxDockWidth();
    const canResize = max > PANEL_MIN;
    handle.hidden = !canResize;
    for (const node of widthValues) {
      node.textContent = state.collapsed ? '已收起' : `${state.applied} px`;
    }
    for (const node of widthMaxes) {
      node.textContent = state.collapsed ? '48 px' : `上限 ${max} px`;
    }
    stageHint.textContent = state.collapsed
      ? '面板已收起 · 展开后可调整宽度'
      : canResize
        ? '拖拽面板左缘调宽 · ← → 每次 16px'
        : '当前视口宽度固定 · 业务区需要时横向滚动';
  }

  function applyWidth(value, options = {}) {
    const width = dockWidth(value);
    const max = maxDockWidth();
    state.applied = width;
    panel.style.setProperty('--panel-w', `${width}px`);
    handle.setAttribute('aria-valuenow', String(width));
    handle.setAttribute('aria-valuemax', String(max));
    handle.setAttribute('aria-valuetext', `${width} 像素，最大 ${max} 像素`);

    if (options.persist) {
      state.width = width;
      saveStoredWidth(width);
    }

    syncWidthUI();

    if (options.announceChange) {
      resizeStatus.textContent = `面板宽度 ${width} 像素`;
    }
  }

  function setCollapsed(collapsed, options = {}) {
    if (state.collapsed === collapsed) return;
    state.collapsed = collapsed;
    panel.dataset.collapsed = String(collapsed);

    if (collapsed) {
      announce('已收起配置助手');
      if (options.moveFocus !== false) expandBtn.focus();
    } else {
      applyWidth(preferredWidth());
      announce('已展开配置助手');
      if (options.moveFocus !== false) collapseBtn.focus();
    }

    syncWidthUI();
  }

  function endResize(event) {
    if (!state.resizing) return;
    state.resizing = false;
    workbench.classList.remove('is-resizing');

    if (
      event &&
      event.pointerId !== undefined &&
      handle.hasPointerCapture &&
      handle.hasPointerCapture(event.pointerId)
    ) {
      handle.releasePointerCapture(event.pointerId);
    }

    state.width = state.applied;
    saveStoredWidth(state.applied);
    syncWidthUI();
    resizeStatus.textContent = `面板宽度 ${state.applied} 像素`;
  }

  handle.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    state.resizing = true;
    state.dragStartX = event.clientX;
    state.dragStartWidth = state.applied;
    workbench.classList.add('is-resizing');
    handle.setPointerCapture(event.pointerId);
  });

  handle.addEventListener('pointermove', (event) => {
    if (!state.resizing) return;
    const delta = state.dragStartX - event.clientX;
    applyWidth(state.dragStartWidth + delta);
  });

  handle.addEventListener('pointerup', endResize);
  handle.addEventListener('pointercancel', endResize);
  handle.addEventListener('lostpointercapture', endResize);

  handle.addEventListener('keydown', (event) => {
    let next = null;

    if (event.key === 'ArrowLeft') next = state.applied + 16;
    else if (event.key === 'ArrowRight') next = state.applied - 16;

    if (next === null) return;
    event.preventDefault();
    applyWidth(next, { persist: true, announceChange: true });
  });

  function handleViewportChange() {
    applyWidth(preferredWidth());
    handle.setAttribute('aria-valuemax', String(maxDockWidth()));
  }

  window.addEventListener('resize', () => {
    if (resizeRaf) return;
    resizeRaf = window.requestAnimationFrame(() => {
      resizeRaf = 0;
      handleViewportChange();
    });
  });

  for (const button of sceneButtons) {
    button.addEventListener('click', () => loadScene(button.dataset.scene));
  }

  newChatBtn.addEventListener('click', requestNewChat);
  collapseBtn.addEventListener('click', () => setCollapsed(true));
  expandBtn.addEventListener('click', () => setCollapsed(false));

  sendBtn.addEventListener('click', () => sendUserText(input.value));
  stopBtn.addEventListener('click', stopRun);

  input.addEventListener('input', updateComposer);
  input.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) {
      return;
    }
    event.preventDefault();
    if (state.running || state.waiting) return;
    sendUserText(input.value);
  });

  messagesEl.addEventListener('click', (event) => {
    if (!(event.target instanceof Element)) return;
    const button = event.target.closest('button');
    if (!button) return;

    if (button.dataset.copyId) {
      copyMessage(button.dataset.copyId, button);
      return;
    }
    if (button.dataset.suggest) {
      sendUserText(button.dataset.suggest);
      return;
    }
    if (button.dataset.cardAction && button.dataset.cardId) {
      resolveCard(button.dataset.cardId, button.dataset.cardAction);
    }
  });

  function copyMessage(messageId, button) {
    const message = state.messages.find((item) => item.id === messageId);
    if (!message) return;
    const text = message.copyText || message.text;

    const done = () => {
      if (!button.isConnected) return;
      button.textContent = '已复制';
      announce('已复制这条回答');
      window.setTimeout(() => {
        if (button.isConnected) button.textContent = '复制';
      }, 1500);
    };

    const failed = () => {
      if (!button.isConnected) return;
      button.textContent = '复制失败';
      window.setTimeout(() => {
        if (button.isConnected) button.textContent = '复制';
      }, 1500);
    };

    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(done).catch(() => {
        if (fallbackCopy(text)) done();
        else failed();
      });
      return;
    }

    if (fallbackCopy(text)) done();
    else failed();
  }

  function fallbackCopy(text) {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.insetBlockStart = '0';
    area.style.insetInlineStart = '0';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    let copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (error) {
      copied = false;
    }
    area.remove();
    return copied;
  }

  dialog.addEventListener('close', () => {
    const choice = dialog.returnValue;
    dialog.returnValue = '';
    dialogNote.hidden = true;
    dialogNote.textContent = '';
    if (choice === 'confirm') startEmptyConversation(false);
  });

  panel.dataset.collapsed = 'false';
  applyWidth(preferredWidth());
  loadScene('done');
})();
