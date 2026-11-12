import { useState } from 'react';

export function MobileShell() {
  const [draft, setDraft] = useState('');
  const [notice, setNotice] = useState('Nakahanda ang Android interface. Hindi pa nakakabit ang lokal na AI engine.');

  function speakStatus() {
    if (!('speechSynthesis' in window)) {
      setNotice('Hindi available ang Android Text-to-Speech sa WebView na ito.');
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance('ZERO mobile interface is ready. The local AI engine is not connected yet.');
    utterance.lang = 'en-US';
    window.speechSynthesis.speak(utterance);
  }

  return (
    <main className="min-h-screen bg-[#080b12] text-white flex flex-col">
      <header className="px-5 pt-8 pb-5 border-b border-white/10">
        <div className="text-xs tracking-[0.35em] text-cyan-300">PERSONAL AI ASSISTANT</div>
        <h1 className="mt-2 text-3xl font-semibold tracking-wide">ZERO</h1>
        <p className="mt-1 text-sm text-slate-400">Android mobile shell</p>
      </header>
      <section className="flex-1 px-5 py-8 flex flex-col gap-5">
        <div className="rounded-2xl border border-cyan-400/20 bg-cyan-400/5 p-5">
          <div className="flex items-center gap-2 text-sm text-cyan-200">
            <span className="h-2 w-2 rounded-full bg-amber-400" />
            LOCAL AI ENGINE NOT CONNECTED
          </div>
          <p className="mt-3 text-sm leading-6 text-slate-300">{notice}</p>
        </div>
        <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-5">
          <h2 className="font-medium">Mobile readiness</h2>
          <ul className="mt-3 space-y-2 text-sm text-slate-400">
            <li>✓ Android-specific app startup</li>
            <li>✓ Desktop backend bypassed</li>
            <li>○ On-device language model</li>
            <li>○ Native microphone recognition</li>
            <li>○ Background wake-word service</li>
          </ul>
        </div>
        <label className="text-sm text-slate-400" htmlFor="zero-message">Message</label>
        <textarea
          id="zero-message"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Local AI engine will be connected in the next milestone…"
          rows={3}
          className="w-full resize-none rounded-xl border border-white/10 bg-white/5 p-4 text-sm text-white outline-none placeholder:text-slate-600"
        />
        <p className="text-xs text-slate-500">Hindi pa nagpapadala ng prompt ang app dahil wala pang nakakabit na lokal na model. Walang pekeng AI response.</p>
        <button
          type="button"
          onClick={speakStatus}
          className="rounded-xl border border-cyan-300/30 bg-cyan-300/10 px-4 py-3 text-sm font-medium text-cyan-100"
        >
          Test voice output
        </button>
      </section>
      <footer className="border-t border-white/10 px-5 py-4 text-center text-xs text-slate-600">
        Offline-first • No paid API configured
      </footer>
    </main>
  );
}
