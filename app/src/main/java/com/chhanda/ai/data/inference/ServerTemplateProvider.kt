package com.chhanda.ai.data.inference

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerTemplateProvider @Inject constructor() {

    fun buildAccessDeniedHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Chhanda AI - Access Denied</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: 'Inter', sans-serif; background: #0f172a; color: #f8fafc; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                .card { background: #1e293b; padding: 2rem; border-radius: 1rem; text-align: center; box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1); border: 1px solid #334155; max-width: 400px; }
                h1 { color: #ef4444; margin-bottom: 1rem; }
                p { color: #94a3b8; line-height: 1.5; }
            </style>
        </head>
        <body>
            <div class="card">
                <h1>Access Denied</h1>
                <p>This Chhanda AI Node is secured. Please provide a valid API Key to connect.</p>
            </div>
        </body>
        </html>
    """.trimIndent()

    fun buildMaxLimitReachedHtml(limit: Int): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Chhanda AI - Limit Reached</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: 'Inter', sans-serif; background: #0f172a; color: #f8fafc; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                .card { background: #1e293b; padding: 2rem; border-radius: 1rem; text-align: center; box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1); border: 1px solid #334155; max-width: 400px; }
                h1 { color: #f59e0b; margin-bottom: 1rem; }
                p { color: #94a3b8; line-height: 1.5; }
            </style>
        </head>
        <body>
            <div class="card">
                <h1>Capacity Reached</h1>
                <p>This node is currently serving its maximum of $limit devices. Please try again later or connect to another Chhanda Node.</p>
            </div>
        </body>
        </html>
    """.trimIndent()

    fun buildChatHtml(
        port: Int, 
        host: String, 
        history: List<Any>, 
        hasConnectedEarlier: Boolean, 
        savedName: String, 
        sessions: List<String>
    ): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>Chhanda AI Gateway</title>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0">
            <script src="https:
            <link href="https:
            <style>
                :root {
                    --primary: #a8c7fa;
                    --on-primary: #062e6f;
                    --surface: #1e1e1e;
                    --on-surface: #e3e3e3;
                    --surface-container: #2d2d2d;
                    --fg: #e3e3e3;
                    --muted: #c4c7c5;
                    --border: #444746;
                    --user-bubble: #0b57d0;
                    --user-text: #ffffff;
                    --ai-bubble: #2d2d2d;
                }

                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                body {
                    background: var(--surface);
                    color: var(--fg);
                    font-family: 'Inter', -apple-system, system-ui, sans-serif;
                    margin: 0;
                    height: 100dvh;
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }

                #app-container {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    max-width: 1000px;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                    margin: 0 auto;
                    position: relative;
                }

                #hdr {
                    padding: 12px 20px;
                    background: rgba(30, 30, 30, 0.8);
                    backdrop-filter: blur(12px);
                    border-bottom: 1px solid var(--border);
                    display: flex;
                    align-items: center;
                    gap: 16px;
                    flex-shrink: 0;
                    z-index: 10;
                }

                #logo { width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; }
                #title { font-weight: 700; font-size: 18px; color: var(--fg); flex: 1; }

                #badge {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    padding: 6px 12px;
                    background: var(--surface-container);
                    border-radius: 100px;
                    font-size: 12px;
                    font-weight: 600;
                    color: var(--muted);
                }
                #badge.on { color: #6dd68c; background: rgba(109, 214, 140, 0.1); }
                #badge.err { color: #f28b82; background: rgba(242, 139, 130, 0.1); }
                #badge.warm { color: #fde293; background: rgba(253, 226, 147, 0.1); }
                #dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; box-shadow: 0 0 8px currentColor; }

                #msgs {
                    flex: 1;
                    overflow-y: auto;
                    padding: 24px 20px;
                    display: flex;
                    flex-direction: column;
                    gap: 24px;
                }

                #msgs::-webkit-scrollbar { width: 6px; }
                #msgs::-webkit-scrollbar-track { background: transparent; }
                #msgs::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }

                .msg-container { display: flex; flex-direction: column; max-width: 85%; animation: fadeIn 0.3s ease; }
                .msg-container.u { align-self: flex-end; }
                .msg-container.a { align-self: flex-start; }
                .msg-container.s { align-self: center; max-width: 100%; width: 100%; }

                .msg {
                    padding: 14px 20px;
                    border-radius: 24px;
                    font-size: 15px;
                    line-height: 1.6;
                    word-wrap: break-word;
                    box-shadow: 0 2px 5px rgba(0,0,0,0.1);
                }

                .msg.u { background: var(--user-bubble); color: var(--user-text); border-bottom-right-radius: 6px; }
                .msg.a { background: var(--ai-bubble); color: var(--fg); border-bottom-left-radius: 6px; border: 1px solid var(--border); }
                .msg.s { background: transparent; color: var(--muted); font-size: 13px; text-align: center; padding: 4px; box-shadow: none; }

                .msg.a p { margin: 0 0 1em 0; }
                .msg.a p:last-child { margin: 0; }
                .msg.a pre { background: #1a1a1a; padding: 12px; border-radius: 8px; overflow-x: auto; border: 1px solid #333; margin: 12px 0; }
                .msg.a code { font-family: monospace; background: rgba(255,255,255,0.1); padding: 2px 4px; border-radius: 4px; font-size: 0.9em; }
                .msg.a pre code { background: transparent; padding: 0; color: #e3e3e3; }
                .msg.a ul, .msg.a ol { margin: 0 0 1em 0; padding-left: 24px; }
                .msg.a table { border-collapse: collapse; width: 100%; margin: 12px 0; }
                .msg.a th, .msg.a td { border: 1px solid var(--border); padding: 8px; text-align: left; }
                .msg.a th { background: rgba(255,255,255,0.05); }
                .msg.a blockquote { border-left: 4px solid var(--primary); margin: 0; padding-left: 12px; color: var(--muted); }

                #ftr { padding: 16px 20px; background: rgba(30, 30, 30, 0.9); backdrop-filter: blur(12px); border-top: 1px solid var(--border); flex-shrink: 0; }
                #input-line { display: flex; align-items: flex-end; gap: 12px; width: 100%; background: var(--surface-container); border-radius: 24px; border: 1px solid var(--border); padding: 6px; transition: border-color 0.2s; }
                #input-line:focus-within { border-color: var(--primary); box-shadow: 0 0 0 1px var(--primary); }

                #inp { 
                    flex: 1; 
                    background: transparent; 
                    border: none; 
                    color: var(--fg); 
                    font-size: 16px; 
                    padding: 12px 16px; 
                    outline: none; 
                    min-height: 44px; 
                    max-height: 400px; 
                    resize: none; 
                    font-family: inherit; 
                    line-height: 1.5;
                }
                #inp::placeholder { color: var(--muted); }

                #btn { width: 40px; height: 40px; border-radius: 50%; background: var(--primary); border: none; color: var(--on-primary); cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; transition: all 0.2s; }
                #btn:disabled { opacity: 0.5; cursor: not-allowed; background: var(--surface-container); color: var(--muted); }
                #btn:not(:disabled):hover { transform: scale(1.05); background: #bcdcff; }

                @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

                @media (max-width: 600px) { 
                    .msg-container { max-width: 92%; } 
                    #title { font-size: 16px; }
                    #hdr { padding: 12px; gap: 8px; }
                    #badge span { display: none; } 
                }
            </style>
        </head>
        <body>
            <div id="app-container">
                <div id="hdr">
                    <div id="logo">
                        <svg width="28" height="28" viewBox="0 0 108 108">
                            <path d="M 70,30 A 28,28 0 1,0 70,78" stroke="var(--primary)" stroke-width="8" stroke-linecap="round" fill="none"/>
                            <path d="M 46,44 h 5 v 20 h -5 z" fill="#6dd68c"/><path d="M 56,38 h 5 v 32 h -5 z" fill="#f28b82"/><path d="M 66,48 h 5 v 12 h -5 z" fill="#a8c7fa"/>
                        </svg>
                    </div>
                    <div id="badge"><div id="dot"></div><span id="bt">CONNECTING</span></div>
                    <div style="flex:1"></div>
                    <button id="close-btn" style="background: transparent; border: none; color: var(--fg); font-size: 20px; cursor: pointer; padding: 0 8px;" onclick="window.close()">&times;</button>
                </div>

                <div id="msgs">
                    <div class="msg-container s" id="establishing-msg"><div class="msg s">Establishing secure link...</div></div>
                </div>

                <div id="ftr">
                    <div id="preview-area" style="display:none; gap: 8px; margin-bottom: 8px; padding: 4px; overflow-x: auto; white-space: nowrap;"></div>
                    <div id="input-line">
                        <input type="file" id="file-inp" style="display:none" />
                        <button id="attach-btn" style="background: transparent; border: none; color: var(--muted); cursor: pointer; padding: 8px;">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
                        </button>
                        <textarea id="inp" placeholder="Waiting for node..." disabled rows="1"></textarea>
                        <button id="btn" disabled>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
                        </button>
                    </div>
                </div>
            </div>

            <script>
                const msgs = document.getElementById('msgs');
                const inp = document.getElementById('inp');
                const btn = document.getElementById('btn');
                const bt = document.getElementById('bt');
                const badge = document.getElementById('badge');
                let ready = false;
                let isThinkingOn = true;
                const apiKeyParam = new URLSearchParams(window.location.search).get('key') || '';

                let userName = localStorage.getItem('chhanda_username');

                let currentAttachments = [];
                const fileInp = document.getElementById('file-inp');
                const attachBtn = document.getElementById('attach-btn');
                const previewArea = document.getElementById('preview-area');

                attachBtn.onclick = () => fileInp.click();
                fileInp.onchange = async (e) => {
                    for (const f of e.target.files) {
                        const reader = new FileReader();
                        reader.onload = (re) => {
                            currentAttachments.push({name: f.name, type: f.type, data: re.target.result});
                            renderPreviews();
                        };
                        reader.readAsDataURL(f);
                    }
                    fileInp.value = '';
                };

                function renderPreviews() {
                    if (currentAttachments.length === 0) { previewArea.style.display = 'none'; return; }
                    previewArea.style.display = 'flex';
                    previewArea.innerHTML = '';
                    currentAttachments.forEach((att, idx) => {
                        const d = document.createElement('div');
                        d.style.cssText = 'background: rgba(255,255,255,0.1); padding: 4px 8px; border-radius: 4px; display: flex; align-items: center; gap: 8px; font-size: 12px; border: 1px solid var(--border);';
                        d.innerHTML = `<span>📎 ${'$'}{att.name}</span><button style="background:none;border:none;color:var(--muted);cursor:pointer" onclick="currentAttachments.splice(${'$'}{idx}, 1); renderPreviews()">×</button>`;
                        previewArea.appendChild(d);
                    });
                }
                if (!userName) {
                    userName = prompt("Welcome to Chhanda! Please enter your name:");
                    if (!userName) userName = "User";
                    localStorage.setItem('chhanda_username', userName);
                    fetch('/register', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({name: userName})
                    }).catch(e => console.log(e));
                }

                inp.addEventListener('input', function() {
                    this.style.height = 'auto';
                    this.style.height = (this.scrollHeight) + 'px';
                    if(this.value.trim().length > 0 && ready) btn.disabled = false;
                    else btn.disabled = true;
                });

                inp.addEventListener('keydown', function(e) {
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        if (!btn.disabled) btn.click();
                    }
                });

                async function pulse() {
                    try {
                        const r = await fetch('/status', {
                            headers: {'X-API-KEY': apiKeyParam}
                        });
                        const d = await r.json();
                        ready = d.modelLoaded;
                        if (d.thinkingMode !== undefined) {
                            isThinkingOn = d.thinkingMode;
                        }
                        if(ready) { 
                            badge.className = 'on'; bt.textContent = 'ONLINE'; 
                            inp.disabled = false; 
                            const estMsg = document.getElementById('establishing-msg');
                            if (estMsg) estMsg.style.display = 'none';
                            if(inp.value.trim() === '') btn.disabled = true;
                            inp.placeholder = 'Type a message...'; 
                        } else {
                            badge.className = 'warm'; bt.textContent = 'AI LOADING';
                            inp.disabled = true; btn.disabled = true;
                        }
                    } catch(e) { 
                        badge.className = 'err'; bt.textContent = 'OFFLINE'; 
                        inp.disabled = true; btn.disabled = true;
                    }
                }

                setInterval(pulse, 3000); pulse();

                btn.onclick = async () => {
                    const txt = inp.value.trim(); if(!txt) return;
                    inp.value = ''; 
                    inp.style.height = 'auto';
                    btn.disabled = true;

                    addMsg(txt, 'u');
                    const aiContainer = addMsg('<span style="color:var(--muted)">...</span>', 'a');

                    try {
                        const payload = {
                            text: txt, 
                            language: 'en',
                            persona: 'Default',
                            attachments: currentAttachments
                        };

                        currentAttachments = [];
                        renderPreviews();

                        const res = await fetch('/chat', {
                            method: 'POST', 
                            headers: {
                                'Content-Type': 'application/json',
                                'X-API-KEY': apiKeyParam
                            },
                            body: JSON.stringify(payload)
                        });

                        const reader = res.body.getReader(); 
                        const dec = new TextDecoder();
                        let rawMarkdown = '';

                        while(true) {
                            const {done, value} = await reader.read(); 
                            if(done) break;
                            const chunkText = dec.decode(value);
                            const parts = chunkText.split('\n\n');

                            for (const part of parts) {
                                if (!part.trim()) continue;
                                if (part.startsWith('data: ERR:')) {
                                    rawMarkdown += '\n\n**Error:** ' + part.substring(10);
                                    continue;
                                }
                                const tok = part.replace('data: ', '');
                                if(tok === '[DONE]') break;

                                rawMarkdown += tok.replace(/\\\\n/g, '\\n');

                                let displayMarkdown = rawMarkdown;
                                if (!isThinkingOn) {
                                    displayMarkdown = displayMarkdown.replace(/<(?:thought|think)>[\s\S]*?(?:<\/(?:thought|think)>|$)/gi, '').trim();
                                    displayMarkdown = displayMarkdown.replace(/^(?:Thinking\.\.\.|Thought:|Reasoning:)\s*/i, '');
                                } else {

                                    const thinkingRegex = /<(?:thought|think)>([\s\S]*?)(?:<\/(?:thought|think)>|$)/i;
                                    const match = displayMarkdown.match(thinkingRegex);
                                    if (match) {
                                        const thought = match[1];
                                        const rest = displayMarkdown.replace(thinkingRegex, '').trim();
                                        displayMarkdown = `<details style="background:rgba(255,255,255,0.05); border-radius:8px; padding:8px; margin-bottom:12px; font-size:13px; border:1px solid var(--border)"><summary style="cursor:pointer; color:var(--primary); font-weight:600">Thinking Process</summary><div style="margin-top:8px; color:var(--muted)">${'$'}{thought}</div></details>${'$'}{rest}`;
                                    }
                                }

                                if (displayMarkdown.trim() === '' && !isThinkingOn) {
                                    aiContainer.innerHTML = '<span style="color:var(--muted)">AI is reasoning...</span>';
                                } else if (typeof marked !== 'undefined') {
                                    aiContainer.innerHTML = marked.parse(displayMarkdown);
                                } else {
                                    aiContainer.textContent = displayMarkdown;
                                }
                                msgs.scrollTop = msgs.scrollHeight;
                            }
                        }
                    } catch(e) {
                        aiContainer.innerHTML = `<span style="color:#f28b82">Connection error: ${'$'}{e.message}</span>`;
                    }
                    if(inp.value.trim().length > 0 && ready) btn.disabled = false;
                };

                function addMsg(t, c) {
                    const d = document.createElement('div'); d.className = 'msg-container ' + c;
                    const m = document.createElement('div'); m.className = 'msg ' + c;
                    if (c === 'user') {
                        m.textContent = t; 
                    } else {
                        m.innerHTML = t; 
                    }
                    d.appendChild(m);
                    msgs.appendChild(d); msgs.scrollTop = msgs.scrollHeight;
                    return m;
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
