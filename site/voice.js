/* Saathi voice commands for the website.
   Pages call SaathiVoice.register([...commands]). Each command has
   match(text) -> truthy result, and run(result, text) -> reply string (or Promise of one).
   Matching is plain keyword rules, no AI. Risky changes open the page's confirm
   dialog, which this file answers by voice: clear yes confirms, unclear asks once more, then cancels. */
(() => {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const synth = window.speechSynthesis;
  const commands = [];
  let busy = false;
  let lastVoiceAt = 0;

  /* ---------- language: follows the settings page choice ---------- */
  function lang() {
    try {
      const s = JSON.parse(localStorage.getItem('saathi-settings-v1'));
      if (s && s.lang === 'hi') return 'hi-IN';
    } catch (e) {}
    return 'en-IN';
  }

  /* ---------- UI ---------- */
  const css = `
  .sv-btn { position: fixed; right: 20px; bottom: 20px; z-index: 60; display: flex; align-items: center; gap: 10px;
    padding: 10px 18px 10px 10px; border-radius: 999px; border: 0; cursor: pointer; background: #17153b; color: #fff;
    font: 600 0.95rem/1 "Mukta", system-ui, sans-serif; box-shadow: 0 18px 36px -14px rgba(23,21,59,.6); }
  .sv-btn:focus-visible { outline: 3px solid #4338f2; outline-offset: 3px; }
  .sv-orb { width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; flex: none;
    background: conic-gradient(from 0deg, #4338f2, #ff5c93, #ffb627, #19c99a, #4338f2); }
  .sv-orb svg { width: 18px; height: 18px; background: #17153b; border-radius: 50%; padding: 6px; box-sizing: content-box; }
  .sv-btn kbd { font: 400 0.7rem/1 "IBM Plex Mono", monospace; opacity: .65; }
  .sv-btn.listening .sv-orb { animation: sv-pulse 1s ease-in-out infinite; }
  @keyframes sv-pulse { 50% { transform: scale(1.15); box-shadow: 0 0 0 8px rgba(255,92,147,.25); } }
  .sv-panel { position: fixed; right: 20px; bottom: 84px; z-index: 60; width: min(360px, calc(100vw - 40px)); background: #fff;
    color: #17153b; border-radius: 22px; padding: 18px 20px; box-shadow: 0 30px 60px -24px rgba(23,21,59,.55);
    font: 400 0.95rem/1.45 "Mukta", system-ui, sans-serif; }
  .sv-panel[hidden] { display: none; }
  .sv-state { font: 500 0.7rem/1 "IBM Plex Mono", monospace; letter-spacing: .08em; text-transform: uppercase; color: #4338f2; margin: 0 0 8px; }
  .sv-heard { margin: 0 0 8px; font-weight: 600; min-height: 1.4em; }
  .sv-heard:empty::before { content: "…"; color: #9a97b8; }
  .sv-reply { margin: 0 0 10px; color: #4b4870; }
  .sv-reply:empty { display: none; }
  .sv-tips { margin: 0; font-size: 0.82rem; color: #4b4870; }
  .sv-tips b { color: #17153b; }
  .sv-privacy { margin: 10px 0 0; font-size: 0.72rem; color: #6e6b90; }
  @media (prefers-reduced-motion: reduce) { .sv-btn.listening .sv-orb { animation: none; box-shadow: 0 0 0 4px #ff5c93; } }
  @media (max-width: 520px) { .sv-btn span.sv-label, .sv-btn kbd { display: none; } .sv-btn { padding: 10px; } }`;
  const style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'sv-btn';
  btn.setAttribute('aria-label', 'Voice commands. Press to speak a command. Shortcut: Alt plus S.');
  btn.setAttribute('aria-pressed', 'false');
  btn.innerHTML = `<span class="sv-orb" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></span><span class="sv-label">Talk</span><kbd>Alt+S</kbd>`;

  const panel = document.createElement('div');
  panel.className = 'sv-panel';
  panel.hidden = true;
  panel.innerHTML = `<p class="sv-state"></p><p class="sv-heard"></p><p class="sv-reply"></p>
    <p class="sv-tips">Say <b>“help”</b> to hear what you can say. Hindi, English or Hinglish all work.</p>
    <p class="sv-privacy">Uses your browser’s speech recognition. In Chrome, your voice is sent to Google to be turned into text.</p>`;
  const elState = panel.querySelector('.sv-state');
  const elHeard = panel.querySelector('.sv-heard');
  const elReply = panel.querySelector('.sv-reply');

  document.addEventListener('DOMContentLoaded', () => { document.body.appendChild(panel); document.body.appendChild(btn); });
  if (document.readyState !== 'loading') { document.body.appendChild(panel); document.body.appendChild(btn); }

  /* ---------- speech out ---------- */
  function say(text, langOverride) {
    text = String(text).replace(/([.?!])\s*\./g, '$1').replace(/\s+/g, ' ').trim();
    return new Promise(resolve => {
      elReply.textContent = text;
      if (!synth) return resolve();
      synth.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.lang = langOverride || (/[ऀ-ॿ]/.test(text) ? 'hi-IN' : 'en-IN');
      const v = synth.getVoices().find(v => v.lang === u.lang);
      if (v) u.voice = v;
      let done = false;
      const fin = () => { if (!done) { done = true; resolve(); } };
      u.onend = fin; u.onerror = fin;
      setTimeout(fin, Math.min(20000, 1500 + text.length * 90));
      synth.speak(u);
    });
  }

  /* ---------- speech in (one phrase at a time) ---------- */
  let rec = null;
  function listenOnce() {
    return new Promise(resolve => {
      rec = new SR();
      rec.lang = lang();
      rec.interimResults = true;
      rec.maxAlternatives = 1;
      let finalText = '', settled = false;
      const timer = setTimeout(() => { try { rec.abort(); } catch (e) {} end({ error: 'timeout' }); }, 8000);
      const end = val => { if (!settled) { settled = true; clearTimeout(timer); btn.classList.remove('listening'); btn.setAttribute('aria-pressed', 'false'); resolve(val); } };
      rec.onresult = e => {
        let t = '';
        for (const r of e.results) t += r[0].transcript;
        elHeard.textContent = t;
        if (e.results[e.results.length - 1].isFinal) finalText = t;
      };
      rec.onerror = e => end({ error: e.error });
      rec.onend = () => end({ text: finalText || elHeard.textContent });
      elState.textContent = 'Listening…';
      elHeard.textContent = '';
      btn.classList.add('listening');
      btn.setAttribute('aria-pressed', 'true');
      try { rec.start(); } catch (e) { end({ error: 'start' }); }
    });
  }

  /* ---------- matching helpers ---------- */
  const norm = s => (s || '').toLowerCase().replace(/[.,!?।"“”'’]/g, ' ').replace(/\s+/g, ' ').trim();
  function has(t, list) {
    const words = t.split(' ');
    return list.some(w => (w.includes(' ') ? t.includes(w) : words.includes(w) || (/[ऀ-ॿ]/.test(w) && t.includes(w))));
  }
  const YES = ['haan', 'haa', 'ha', 'han', 'yes', 'yeah', 'confirm', 'kar do', 'karo', 'ok', 'okay', 'theek hai', 'हाँ', 'हां', 'ठीक है', 'हा'];
  const NO = ['nahi', 'nahin', 'na', 'no', 'cancel', 'ruko', 'mat', 'नहीं', 'रुको', 'मत', 'ना'];
  function yesNo(t) {
    const y = has(t, YES), n = has(t, NO);
    if (y && !n) return 'yes';
    if (n && !y) return 'no';
    return 'unclear';
  }

  /* ---------- built-in commands (both pages) ---------- */
  const STOP = ['stop', 'ruko', 'ruk', 'band karo', 'chup', 'quiet', 'रुको', 'बंद करो', 'चुप'];
  const builtins = [
    { match: t => has(t, ['help', 'madad', 'what can i say', 'kya bol', 'मदद']), run: () => helpText() },
    { match: t => has(t, STOP) && t.split(' ').length <= 3, run: () => { if (synth) synth.cancel(); document.dispatchEvent(new CustomEvent('saathi-voice-stop')); return 'Stopped.'; } },
    { match: t => has(t, ['scroll down', 'neeche', 'niche', 'नीचे', 'page down']), run: () => { scrollBy({ top: innerHeight * 0.8, behavior: 'smooth' }); return 'Scrolled down.'; } },
    { match: t => has(t, ['scroll up', 'upar', 'ऊपर', 'page up']), run: () => { scrollBy({ top: -innerHeight * 0.8, behavior: 'smooth' }); return 'Scrolled up.'; } },
  ];
  let helpText = () => 'You can say: go to a section, scroll down, scroll up, stop, or help.';

  /* ---------- main flow ---------- */
  async function handle(raw) {
    const t = norm(raw);
    if (!t) return say('I didn’t hear anything. Press Talk and try again.');
    lastVoiceAt = Date.now();
    for (const c of [...commands, ...builtins]) {
      const m = c.match(t);
      if (m) {
        let reply = await c.run(m, t);
        if (reply) { reply = String(reply).replace(/([.?!])\s*\./g, '$1'); await say(reply); }
        return reply;
      }
    }
    const miss = 'Sorry, I didn’t understand that. Say help to hear what you can say.';
    await say(miss);
    return miss;
  }

  async function start() {
    if (busy) { if (rec) rec.abort(); return; }
    panel.hidden = false;
    if (!SR) {
      elState.textContent = 'Not available';
      elHeard.textContent = '';
      await say('Voice commands work in Chrome and Edge. In this browser, please use your screen reader or keyboard.');
      return;
    }
    busy = true;
    if (synth) synth.cancel();
    const r = await listenOnce();
    busy = false;
    elState.textContent = 'Heard';
    if (r.error) {
      const msg = r.error === 'not-allowed' || r.error === 'service-not-allowed'
        ? 'The microphone is blocked. Allow the microphone for this page in your browser, then try again.'
        : r.error === 'no-speech' ? 'I didn’t hear anything. Press Talk and try again.'
        : r.error === 'aborted' ? 'Stopped listening.'
        : r.error === 'timeout' ? 'I didn’t hear a command. Press Talk and try again.'
        : 'Voice recognition isn’t working right now. Please try again.';
      return say(msg);
    }
    elState.textContent = 'You said';
    return handle(r.text);
  }

  btn.addEventListener('click', start);
  document.addEventListener('keydown', e => {
    if (e.altKey && e.code === 'KeyS') { e.preventDefault(); start(); }
    if (e.key === 'Escape' && busy && rec) rec.abort();
  });

  /* ---------- answer the page's confirm dialog by voice ---------- */
  function watchDialog(dlg, yesBtn, noBtn) {
    new MutationObserver(async () => {
      if (!dlg.open || Date.now() - lastVoiceAt > 8000 || !SR) return;
      const title = dlg.querySelector('h2')?.textContent || '';
      const body = dlg.querySelector('p')?.textContent || '';
      await say(`${title} ${body} Say yes or no.`);
      for (let attempt = 0; attempt < 2 && dlg.open; attempt++) {
        const r = await listenOnce();
        const ans = r.error ? 'unclear' : yesNo(norm(r.text));
        if (ans === 'yes') { yesBtn.click(); await say('Done. Changed.'); return; }
        if (ans === 'no') { noBtn.click(); await say('Cancelled. Nothing changed.'); return; }
        if (attempt === 0) await say('Sorry, please say yes or no.');
      }
      if (dlg.open) { noBtn.click(); await say('I didn’t get a clear yes, so I cancelled. Nothing changed.'); }
    }).observe(dlg, { attributes: true, attributeFilter: ['open'] });
  }

  /* ---------- shared helper: jump to a section by its heading ---------- */
  function goTo(headingId) {
    const h = document.getElementById(headingId);
    if (!h) return null;
    const sec = h.closest('section') || h;
    sec.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
    h.setAttribute('tabindex', '-1');
    h.focus({ preventScroll: true });
    return h.textContent.trim();
  }

  window.SaathiVoice = {
    register: list => commands.push(...list),
    setHelp: fn => { helpText = fn; },
    watchDialog, goTo, has, norm, say, run: handle,
  };
})();
