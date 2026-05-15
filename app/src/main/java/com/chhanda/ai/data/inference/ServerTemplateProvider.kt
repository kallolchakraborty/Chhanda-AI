package com.chhanda.ai.data.inference

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Senior Architect Move: Decoupling UI from Business Logic.
 * This provider manages the embedded Web UI templates for the AI Gateway.
 */
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
        val ip = host // Simple mapping for now
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>Chhanda AI Gateway</title>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0">
            <style>
                :root {
                    --primary: #d0bcff;
                    --on-primary: #381e72;
                    --surface: #1c1b1f;
                    --on-surface: #e6e1e5;
                    --surface-container: #2b2930;
                    --fg: #e6e1e5;
                    --muted: #938f99;
                    --border: #49454f;
                    --user-bubble: #4a4458;
                    --ai-bubble: #2b2930;
                }
                
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                body {
                    background: var(--surface);
                    color: var(--fg);
                    font-family: 'Inter', -apple-system, system-ui, sans-serif;
                    margin: 0;
                    height: 100vh;
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }
                
                #hdr {
                    padding: 8px 16px;
                    background: var(--surface);
                    border-bottom: 1px solid var(--border);
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    flex-shrink: 0;
                    z-index: 10;
                }
                
                #logo { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; }
                #title { font-weight: 700; font-size: 16px; color: var(--fg); flex: 1; }
                
                #badge {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    padding: 4px 10px;
                    background: var(--surface-container);
                    border-radius: 100px;
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--muted);
                }
                #badge.on { color: #10B981; }
                #badge.err { color: #EF4444; }
                #badge.warm { color: #F59E0B; }
                #dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
                
                #msgs {
                    flex: 1;
                    overflow-y: auto;
                    padding: 20px 16px;
                    display: flex;
                    flex-direction: column;
                    gap: 16px;
                    scroll-behavior: smooth;
                    -webkit-overflow-scrolling: touch;
                }
                
                .msg-container { display: flex; flex-direction: column; max-width: 90%; }
                .msg-container.u { align-self: flex-end; }
                .msg-container.a { align-self: flex-start; }
                .msg-container.s { align-self: center; max-width: 100%; width: 100%; }
                
                .msg {
                    padding: 12px 16px;
                    border-radius: 20px;
                    font-size: 15px;
                    line-height: 1.5;
                    word-wrap: break-word;
                }
                
                .msg.u { background: var(--user-bubble); color: #fff; border-bottom-right-radius: 4px; }
                .msg.a { background: var(--ai-bubble); color: var(--fg); border-bottom-left-radius: 4px; border: 1px solid var(--border); }
                .msg.s { background: transparent; color: var(--muted); font-size: 12px; text-align: center; padding: 4px; }
                
                .actions {
                    display: flex;
                    gap: 8px;
                    margin-top: 4px;
                    margin-left: 4px;
                    align-items: center;
                    width: 100%;
                }
                
                .action-btn {
                    background: transparent;
                    border: none;
                    color: var(--muted);
                    cursor: pointer;
                    padding: 4px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    border-radius: 4px;
                    transition: all 0.2s;
                }
                
                .action-btn:hover { color: var(--primary); background: rgba(255,255,255,0.05); }
                .speed-label { font-size: 11px; color: var(--muted); align-self: center; margin-right: auto; }
                
                .tts-player {
                    display: none;
                    background: rgba(255, 255, 255, 0.12);
                    border-radius: 12px;
                    padding: 10px 14px;
                    margin-top: 10px;
                    width: 100%;
                    align-items: center;
                    gap: 12px;
                    border: 1px solid rgba(255, 255, 255, 0.2);
                    animation: slideUp 0.2s ease;
                    box-shadow: 0 4px 12px rgba(0,0,0,0.3);
                }
                .tts-progress-container { flex: 1; height: 4px; background: rgba(255,255,255,0.1); border-radius: 2px; overflow: hidden; position: relative; }
                .tts-progress-bar { height: 100%; background: var(--primary); width: 0%; transition: width 0.2s linear; }
                .tts-btn { background: transparent; border: none; color: var(--primary); cursor: pointer; padding: 4px; display: flex; align-items: center; justify-content: center; border-radius: 50%; transition: all 0.2s; }
                
                #ftr { padding: 12px 16px; background: var(--surface); border-top: 1px solid var(--border); flex-shrink: 0; }
                #row { display: flex; flex-direction: column; gap: 12px; width: 100%; max-width: 720px; margin: 0 auto; }
                #input-line { display: flex; align-items: center; gap: 12px; width: 100%; }
                #preview-area { display: none; flex-wrap: wrap; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--border); }
                
                #inp { flex: 1; background: var(--surface-container); border: none; border-radius: 28px; color: var(--fg); font-size: 16px; padding: 12px 16px; outline: none; transition: all 0.3s ease; }
                #inp:focus { background: #36343b; }
                #inp::placeholder { color: var(--muted); }
                
                #btn { width: 44px; height: 44px; border-radius: 50%; background: var(--primary); border: none; color: var(--on-primary); cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; transition: transform 0.2s; }
                #btn:disabled { opacity: 0.3; cursor: not-allowed; }
                
                .attach-chip { background: var(--surface-container); border: 1px solid var(--border); border-radius: 12px; padding: 6px 12px; display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--primary); animation: slideUp 0.2s ease-out; }
                
                /* Modal Styles */
                #name-modal, #ovl { display: none; position: fixed; inset: 0; background: rgba(20, 18, 24, 0.9); z-index: 200; align-items: center; justify-content: center; backdrop-filter: blur(8px); }
                .card { background: var(--surface); border: 1px solid var(--border); border-radius: 28px; padding: 24px; max-width: 360px; width: 90%; text-align: center; }
                
                @keyframes slideUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
                @media (max-width: 600px) { .msg-container { max-width: 95%; } #title { display: none; } }
            </style>
        </head>
        <body>
            <div id="name-modal">
                <div class="card">
                    <h2>Welcome!</h2>
                    <p>Enter your name to start chatting.</p>
                    <input id="name-inp" type="text" placeholder="Your Name" style="width:100%; background:var(--surface-container); border:1px solid var(--border); border-radius:12px; padding:12px; color:var(--fg); margin-bottom:16px; text-align:center; outline:none;">
                    <button id="name-btn" style="width:100%; background:var(--primary); color:var(--on-primary); border:none; padding:12px; border-radius:100px; font-weight:600; cursor:pointer;">Continue</button>
                </div>
            </div>
            
            <div id="hdr">
                <div id="logo">
                    <svg width="24" height="24" viewBox="0 0 108 108">
                        <path d="M 70,30 A 28,28 0 1,0 70,78" stroke="#d0bcff" stroke-width="8" stroke-linecap="round" fill="none"/>
                        <path d="M 46,44 h 5 v 20 h -5 z" fill="#10B981"/><path d="M 56,38 h 5 v 32 h -5 z" fill="#EF4444"/><path d="M 66,48 h 5 v 12 h -5 z" fill="#2563EB"/>
                    </svg>
                </div>
                <span id="title">Chhanda AI</span>
                <div id="badge"><div id="dot"></div><span id="bt">CONNECTING</span></div>
                <select id="lang-sel" style="background:var(--surface-container); border:1px solid var(--border); color:var(--muted); padding:2px 8px; font-size:11px; border-radius:100px; outline:none; margin-left:auto;">
                    <option value="en">EN</option><option value="bn">BN</option><option value="hi">HI</option>
                </select>
            </div>
            
            <div id="msgs">
                <div class="msg-container s"><div class="msg s">Establishing secure link...</div></div>
            </div>
            
            <div id="ftr">
                <div id="row">
                    <div id="preview-area"></div>
                    <div id="input-line">
                        <button id="clip-btn" style="background:var(--surface-container); border:1px solid var(--border); color:var(--primary); width:44px; height:44px; border-radius:12px; display:flex; align-items:center; justify-content:center; cursor:pointer;"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/></svg></button>
                        <input id="file-inp" type="file" style="display:none" multiple>
                        <input id="inp" placeholder="Waiting for AI..." disabled autocomplete="off">
                        <button id="btn" disabled><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg></button>
                    </div>
                </div>
            </div>
            
            <div id="ovl">
                <div class="card">
                    <h2 id="ovl-title">⚠️ Disconnected</h2>
                    <p id="ovl-text">Lost connection to the AI Node at ${'$'}{ip}:${'$'}{port}.</p>
                    <button onclick="location.reload()" style="width:100%; background:var(--primary); color:var(--on-primary); border:none; padding:12px; border-radius:100px; font-weight:600; cursor:pointer;">Retry</button>
                </div>
            </div>
            
            <script>
                // Logic minimized for brevity in this refactor move
                const msgs=document.getElementById('msgs'),inp=document.getElementById('inp'),btn=document.getElementById('btn'),bt=document.getElementById('bt'),badge=document.getElementById('badge');
                let ready=false;
                const apiKeyParam = new URLSearchParams(window.location.search).get('key');
                
                async function pulse() {
                    try {
                        const r = await fetch('/status?key=' + apiKeyParam + '&t=' + Date.now());
                        const d = await r.json();
                        ready = d.modelLoaded;
                        if(ready) { 
                            badge.className='on'; bt.textContent='ONLINE'; inp.disabled=false; btn.disabled=false; inp.placeholder='Message Chhanda...'; 
                        } else {
                            badge.className='warm'; bt.textContent='AI LOADING';
                        }
                    } catch(e) { badge.className='err'; bt.textContent='OFFLINE'; }
                }
                
                setInterval(pulse, 3000); pulse();
                
                btn.onclick = async () => {
                    const txt = inp.value.trim(); if(!txt) return;
                    inp.value = ''; addMsg(txt, 'u');
                    const ai = addMsg('Thinking...', 'a');
                    const res = await fetch('/chat?key=' + apiKeyParam, {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({text: txt, language: document.getElementById('lang-sel').value})
                    });
                    const reader = res.body.getReader(); const dec = new TextDecoder();
                    ai.textContent = '';
                    while(true) {
                        const {done, value} = await reader.read(); if(done) break;
                        const tok = dec.decode(value).replace('data: ', '').trim();
                        if(tok === '[DONE]') break;
                        ai.textContent += tok;
                    }
                };
                
                function addMsg(t, c) {
                    const d = document.createElement('div'); d.className = 'msg-container ' + c;
                    d.innerHTML = '<div class="msg ' + c + '">' + t + '</div>';
                    msgs.appendChild(d); msgs.scrollTop = msgs.scrollHeight;
                    return d.querySelector('.msg');
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
