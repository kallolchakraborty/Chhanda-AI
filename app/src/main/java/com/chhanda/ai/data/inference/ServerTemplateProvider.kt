package com.chhanda.ai.data.inference

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerTemplateProvider @Inject constructor() {

    fun buildAccessDeniedHtml(): String = """
<!DOCTYPE html><html><head><title>Chhanda AI - Access Denied</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{box-sizing:border-box}body{font-family:-apple-system,system-ui,'Segoe UI',Roboto,sans-serif;background:#111;color:#e3e3e3;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}.c{background:#1a1a1a;padding:2.5rem;border-radius:1.5rem;text-align:center;border:1px solid #333;max-width:400px}h1{color:#f87171;margin:0 0 1rem}p{color:#9aa0a6;line-height:1.6;margin:0}</style>
</head><body><div class="c"><h1>Access Denied</h1><p>This Chhanda AI Node is secured. Please provide a valid API Key to connect.</p></div></body></html>
    """.trimIndent()

    fun buildMaxLimitReachedHtml(limit: Int): String = """
<!DOCTYPE html><html><head><title>Chhanda AI - Limit Reached</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{box-sizing:border-box}body{font-family:-apple-system,system-ui,'Segoe UI',Roboto,sans-serif;background:#111;color:#e3e3e3;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}.c{background:#1a1a1a;padding:2.5rem;border-radius:1.5rem;text-align:center;border:1px solid #333;max-width:400px}h1{color:#facc15;margin:0 0 1rem}p{color:#9aa0a6;line-height:1.6;margin:0}</style>
</head><body><div class="c"><h1>Capacity Reached</h1><p>This node is currently serving its maximum of $limit devices. Please try again later.</p></div></body></html>
    """.trimIndent()

    fun buildChatHtml(
        port: Int, host: String, history: List<Any>,
        hasConnectedEarlier: Boolean, savedName: String, sessions: List<String>
    ): String {
        return """
<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<title>Chhanda AI</title>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=0">
<style>
:root{--p:#8ab4f8;--bg:#111;--sf:#1a1a1a;--sf2:#222;--bd:rgba(255,255,255,.08);--tx:#e3e3e3;--tx2:#9aa0a6;--ub:#1e3a5f;--ab:#1a1a1a;--g:#34d399;--r:#f87171;--y:#facc15}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{background:var(--bg);color:var(--tx);font-family:-apple-system,system-ui,'Segoe UI',Roboto,sans-serif;margin:0;height:100dvh;display:flex;justify-content:center;overflow:hidden}
#app{flex:1;display:flex;flex-direction:column;max-width:860px;width:100%;height:100%}
#hdr{padding:12px 20px;background:rgba(17,17,17,.85);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);border-bottom:1px solid var(--bd);display:flex;align-items:center;gap:12px;z-index:10}
.logo{display:flex;align-items:center;gap:10px}
#title{font-weight:700;font-size:18px;color:#fff}
#badge{display:flex;align-items:center;gap:6px;padding:5px 12px;border:1px solid var(--bd);border-radius:99px;font-size:10px;font-weight:700;letter-spacing:.5px;color:var(--tx2)}
#badge.on{color:var(--g);border-color:rgba(52,211,153,.25);background:rgba(52,211,153,.06)}
#badge.err{color:var(--r);border-color:rgba(248,113,113,.25);background:rgba(248,113,113,.06)}
#badge.warm{color:var(--y);border-color:rgba(250,204,21,.25);background:rgba(250,204,21,.06)}
#dot{width:6px;height:6px;border-radius:50%;background:currentColor;animation:pulse 2s infinite}
@keyframes pulse{50%{transform:scale(1.6);opacity:.4}}
#msgs{flex:1;overflow-y:auto;padding:24px 16px;display:flex;flex-direction:column;gap:20px;scroll-behavior:smooth}
#msgs::-webkit-scrollbar{width:3px}#msgs::-webkit-scrollbar-thumb{background:var(--bd);border-radius:9px}
.mc{display:flex;flex-direction:column;max-width:88%;animation:up .35s cubic-bezier(.16,1,.3,1)}
.mc.u{align-self:flex-end}.mc.a{align-self:flex-start}.mc.s{align-self:center;max-width:100%;opacity:.5}
@keyframes up{from{opacity:0;transform:translateY(16px)}}
.m{padding:14px 18px;border-radius:22px;font-size:15px;line-height:1.65;word-break:break-word}
.m.u{background:var(--ub);color:#fff;border-bottom-right-radius:4px}
.m.a{background:var(--ab);color:var(--tx);border-bottom-left-radius:4px;border:1px solid var(--bd)}
.m.s{background:0 0;color:var(--tx2);font-size:13px;text-align:center;padding:6px}
.m.a p{margin:0 0 .9em}.m.a p:last-child{margin:0}
.m.a h1,.m.a h2,.m.a h3,.m.a h4{margin:.8em 0 .4em;font-weight:700;color:#fff}
.m.a h1{font-size:1.3em}.m.a h2{font-size:1.15em}.m.a h3{font-size:1.05em}
.m.a ul,.m.a ol{margin:.4em 0;padding-left:1.5em}.m.a li{margin:.2em 0}
.m.a blockquote{border-left:3px solid var(--p);margin:.5em 0;padding:.2em .8em;color:var(--tx2);background:rgba(255,255,255,.02);border-radius:0 8px 8px 0}
.m.a table{border-collapse:collapse;width:100%;margin:.6em 0;font-size:.9em}
.m.a th,.m.a td{border:1px solid var(--bd);padding:6px 10px;text-align:left}
.m.a th{background:rgba(255,255,255,.04);font-weight:600}
.m.a hr{border:none;border-top:1px solid var(--bd);margin:1em 0}
.m.a a{color:var(--p);text-decoration:none}
.m.a strong{color:#fff;font-weight:600}
.m.a em{color:var(--tx2)}
.cb-wrap{position:relative;margin:.7em 0;border-radius:12px;overflow:hidden;border:1px solid var(--bd);background:#0d0d0d}
.cb-hdr{display:flex;align-items:center;justify-content:space-between;padding:6px 12px;background:rgba(255,255,255,.04);font-size:11px;color:var(--tx2);font-weight:600}
.cb-hdr button{background:0 0;border:none;color:var(--tx2);cursor:pointer;font-size:11px;padding:4px 8px;border-radius:6px}
.cb-hdr button:hover{background:rgba(255,255,255,.08);color:#fff}
.m.a pre{margin:0;padding:14px;overflow-x:auto;font-size:13px;line-height:1.5}
.m.a code{font-family:ui-monospace,SFMono-Regular,'SF Mono',Menlo,Consolas,monospace;font-size:.88em;background:rgba(255,255,255,.07);padding:1px 5px;border-radius:5px;color:var(--p)}
.m.a pre code{background:0 0;padding:0;color:var(--tx);font-size:inherit}
.think-box{background:rgba(138,180,248,.04);border:1px solid rgba(138,180,248,.12);border-radius:12px;padding:10px 14px;margin-bottom:.8em}
.think-toggle{display:flex;align-items:center;gap:6px;cursor:pointer;font-size:12px;font-weight:700;color:var(--p);user-select:none;border:none;background:0 0;padding:0;width:100%;text-align:left}
.think-toggle svg{transition:transform .2s}
.think-toggle.open svg{transform:rotate(90deg)}
.think-body{margin-top:8px;font-size:13px;color:var(--tx2);line-height:1.6;display:none}
.think-body.show{display:block}
.typing span{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--tx2);margin:0 2px;animation:blink 1.4s infinite}
.typing span:nth-child(2){animation-delay:.2s}.typing span:nth-child(3){animation-delay:.4s}
@keyframes blink{0%,80%,100%{opacity:.3}40%{opacity:1}}
#ftr{padding:12px 16px 24px;background:rgba(17,17,17,.9);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);border-top:1px solid var(--bd)}
#prev{display:none;gap:8px;padding:0 4px 10px;overflow-x:auto;white-space:nowrap}
.chip{background:rgba(138,180,248,.08);border:1px solid rgba(138,180,248,.15);color:var(--p);padding:5px 10px;border-radius:10px;font-size:11px;font-weight:600;display:inline-flex;align-items:center;gap:6px;animation:pop .2s}
.chip b{cursor:pointer;opacity:.5;font-weight:400}.chip b:hover{opacity:1}
@keyframes pop{from{transform:scale(.85);opacity:0}}
#iw{background:var(--sf);border:1px solid var(--bd);border-radius:28px;padding:6px 10px;display:flex;align-items:flex-end;gap:8px;transition:border-color .2s,box-shadow .2s}
#iw:focus-within{border-color:var(--p);box-shadow:0 0 0 3px rgba(138,180,248,.15)}
#inp{flex:1;background:0 0;border:none;color:var(--tx);font-size:15px;padding:10px;outline:none;min-height:44px;max-height:200px;resize:none;font-family:inherit;line-height:1.5}
#inp::placeholder{color:var(--tx2)}
.ib{width:40px;height:40px;border-radius:50%;border:none;display:flex;align-items:center;justify-content:center;cursor:pointer;transition:all .15s;background:0 0;color:var(--tx2);flex-shrink:0}
.ib:hover{background:rgba(255,255,255,.06);color:var(--tx)}
#btn{background:var(--p);color:var(--bg)}
#btn:disabled{background:rgba(255,255,255,.05);color:var(--tx2);cursor:not-allowed}
#btn:not(:disabled):hover{transform:scale(1.06);box-shadow:0 0 12px rgba(138,180,248,.3)}
@media(max-width:600px){#hdr{padding:10px 12px}#msgs{padding:16px 10px}#ftr{padding:10px 10px 16px}.mc{max-width:94%}#title{font-size:16px}}
</style></head><body>
<div id="app">
<div id="hdr">
<div class="logo">
<svg width="28" height="28" viewBox="0 0 108 108"><path d="M70,30A28,28 0 1,0 70,78" stroke="var(--p)" stroke-width="8" stroke-linecap="round" fill="none"/><rect x="46" y="44" width="5" height="20" fill="var(--g)" opacity=".7"/><rect x="56" y="38" width="5" height="32" fill="var(--r)" opacity=".7"/><rect x="66" y="48" width="5" height="12" fill="var(--p)" opacity=".7"/></svg>
<div id="title">Chhanda</div>
</div>
<div id="badge"><div id="dot"></div><span id="bt">CONNECTING</span></div>
<div style="flex:1"></div>
<button class="ib" onclick="newChat()" title="New Chat"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>
</div>
<div id="msgs"><div class="mc s" id="welcome"><div class="m s">Waiting for AI engine...</div></div></div>
<div id="ftr">
<div id="prev"></div>
<div id="iw">
<input type="file" id="fi" style="display:none" multiple>
<button class="ib" id="ab" title="Attach"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/></svg></button>
<textarea id="inp" placeholder="Connecting..." disabled rows="1"></textarea>
<button id="btn" class="ib" disabled><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M22 2L11 13"/><path d="M22 2L15 22L11 13L2 9L22 2"/></svg></button>
</div></div></div>
<script>
const msgs=document.getElementById('msgs'),inp=document.getElementById('inp'),btn=document.getElementById('btn'),bt=document.getElementById('bt'),badge=document.getElementById('badge'),fi=document.getElementById('fi'),prev=document.getElementById('prev');
let ready=false,thinkOn=true,atts=[],streaming=false;
const K=new URLSearchParams(location.search).get('key')||'';

function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML}

// Inline Markdown Parser — zero dependencies
function md(src){
src=src.replace(/\r\n/g,'\n');
// code blocks
src=src.replace(/```(\w*)\n([\s\S]*?)```/g,function(_,lang,code){
const l=lang||'code';
return '<div class="cb-wrap"><div class="cb-hdr"><span>'+esc(l)+'</span><button onclick="cpCode(this)">Copy</button></div><pre><code>'+esc(code.replace(/\n$/,''))+'</code></pre></div>';
});
// process line by line
const lines=src.split('\n');let html='',inUl=false,inOl=false,inBq=false,inP=false,inTable=false,tableRows=[];
function closeAll(){
if(inUl){html+='</ul>';inUl=false}
if(inOl){html+='</ol>';inOl=false}
if(inBq){html+='</blockquote>';inBq=false}
if(inP){html+='</p>';inP=false}
if(inTable){html+=buildTable(tableRows);tableRows=[];inTable=false}
}
function buildTable(rows){
if(!rows.length)return'';
let t='<table>';
const hdr=rows[0];
t+='<tr>'+hdr.map(c=>'<th>'+inline(c.trim())+'</th>').join('')+'</tr>';
for(let i=2;i<rows.length;i++){
t+='<tr>'+rows[i].map(c=>'<td>'+inline(c.trim())+'</td>').join('')+'</tr>';
}
return t+'</table>';
}
function inline(s){
s=esc(s);
s=s.replace(/`([^`]+)`/g,'<code>$1</code>');
s=s.replace(/\*\*\*(.+?)\*\*\*/g,'<strong><em>$1</em></strong>');
s=s.replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>');
s=s.replace(/__(.+?)__/g,'<strong>$1</strong>');
s=s.replace(/\*(.+?)\*/g,'<em>$1</em>');
s=s.replace(/_(.+?)_/g,'<em>$1</em>');
s=s.replace(/~~(.+?)~~/g,'<del>$1</del>');
s=s.replace(/\[([^\]]+)\]\(([^)]+)\)/g,function(_,text,url){
var u=url.replace(/&amp;/g,'&');
if(!/^https?:\/\//i.test(u))return text;
return '<a href="'+url+'" target="_blank" rel="noopener">'+text+'</a>';
});
return s;
}
for(let i=0;i<lines.length;i++){
const L=lines[i];
// table row
if(L.trim().startsWith('|')&&L.trim().endsWith('|')){
if(!inTable){closeAll();inTable=true;tableRows=[]}
const cells=L.trim().slice(1,-1).split('|');
tableRows.push(cells);continue;
}else if(inTable){html+=buildTable(tableRows);tableRows=[];inTable=false}
// hr
if(/^[-*_]{3,}\s*$/.test(L)){closeAll();html+='<hr>';continue}
// heading
const hm=L.match(/^(#{1,4})\s+(.+)/);
if(hm){closeAll();const lv=hm[1].length;html+='<h'+lv+'>'+inline(hm[2])+'</h'+lv+'>';continue}
// ul
const ulm=L.match(/^[\s]*[-*+]\s+(.+)/);
if(ulm){if(inOl){html+='</ol>';inOl=false}if(inP){html+='</p>';inP=false}if(!inUl){html+='<ul>';inUl=true}html+='<li>'+inline(ulm[1])+'</li>';continue}
// ol
const olm=L.match(/^[\s]*\d+\.\s+(.+)/);
if(olm){if(inUl){html+='</ul>';inUl=false}if(inP){html+='</p>';inP=false}if(!inOl){html+='<ol>';inOl=true}html+='<li>'+inline(olm[1])+'</li>';continue}
// blockquote
if(L.startsWith('>')){const t=L.replace(/^>\s?/,'');if(!inBq){closeAll();html+='<blockquote>';inBq=true}html+=inline(t)+' ';continue}else if(inBq){html+='</blockquote>';inBq=false}
// close lists on non-list line
if(inUl&&!ulm){html+='</ul>';inUl=false}
if(inOl&&!olm){html+='</ol>';inOl=false}
// empty line
if(!L.trim()){closeAll();continue}
// paragraph
if(!inP){html+='<p>';inP=true}else{html+=' '}
html+=inline(L);
}
closeAll();
return html;
}

function cpCode(b){const code=b.closest('.cb-wrap').querySelector('code');navigator.clipboard.writeText(code.textContent).then(()=>{b.textContent='Copied!';setTimeout(()=>b.textContent='Copy',1500)}).catch(()=>{})}

// Thinking process parser
function parseThinking(raw){
const r=/<(?:think|thought)>([\s\S]*?)(?:<\/(?:think|thought)>|$)/i;
const m=raw.match(r);
if(!m)return{thought:null,response:raw};
return{thought:m[1].trim(),response:raw.replace(r,'').trim()};
}

function renderThinking(thought,response){
let h='';
if(thought&&thinkOn){
h+='<div class="think-box"><button class="think-toggle" onclick="this.classList.toggle(\'open\');this.nextElementSibling.classList.toggle(\'show\')"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5l10 7-10 7z"/></svg> Thinking Process</button><div class="think-body">'+md(thought)+'</div></div>';
}
h+=md(response||'...');
return h;
}

document.getElementById('ab').onclick=()=>fi.click();
fi.onchange=e=>{for(const f of e.target.files){const r=new FileReader();r.onload=re=>{atts.push({name:f.name,type:f.type,data:re.target.result});renderPrev()};r.readAsDataURL(f)}fi.value=''};

function renderPrev(){
if(!atts.length){prev.style.display='none';return}
prev.style.display='flex';prev.innerHTML='';
atts.forEach((a,i)=>{const d=document.createElement('div');d.className='chip';d.innerHTML='\u{1F4CE} '+esc(a.name.length>15?a.name.slice(0,12)+'...':a.name)+' <b onclick="atts.splice('+i+',1);renderPrev()">\u2715</b>';prev.appendChild(d)});
}

inp.addEventListener('input',function(){this.style.height='auto';this.style.height=this.scrollHeight+'px';btn.disabled=!(this.value.trim()&&ready&&!streaming)});
inp.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();if(!btn.disabled)btn.click()}});

async function pulse(){
try{const r=await fetch('/status',{headers:{'X-API-KEY':K}});const d=await r.json();ready=d.modelLoaded;thinkOn=d.thinkingMode!==false;
if(ready){badge.className='on';bt.textContent='ONLINE';inp.disabled=false;inp.placeholder='Message Chhanda...';const w=document.getElementById('welcome');if(w)w.remove();if(inp.value.trim()&&!streaming)btn.disabled=false}
else{badge.className='warm';bt.textContent='LOADING';inp.disabled=true;btn.disabled=true}
}catch(e){badge.className='err';bt.textContent='OFFLINE';inp.disabled=true;btn.disabled=true}}
setInterval(pulse,3000);pulse();

function newChat(){msgs.innerHTML='';addMsg('New conversation started.','s')}

function addMsg(t,c){
const ct=document.createElement('div');ct.className='mc '+c;
const m=document.createElement('div');m.className='m '+c;
if(c==='u')m.innerHTML=esc(t).replace(/\n/g,'<br>');
else if(c==='s')m.textContent=t;
else m.innerHTML=md(t);
ct.appendChild(m);msgs.appendChild(ct);msgs.scrollTop=msgs.scrollHeight;return m;
}

btn.onclick=async()=>{
const txt=inp.value.trim();if(!txt&&!atts.length)return;
inp.value='';inp.style.height='auto';btn.disabled=true;streaming=true;
addMsg(txt,'u');
const aiEl=document.createElement('div');aiEl.className='mc a';
const aiMsg=document.createElement('div');aiMsg.className='m a';
aiMsg.innerHTML='<div class="typing"><span></span><span></span><span></span></div>';
aiEl.appendChild(aiMsg);msgs.appendChild(aiEl);msgs.scrollTop=msgs.scrollHeight;

try{
const res=await fetch('/chat',{method:'POST',headers:{'Content-Type':'application/json','X-API-KEY':K},body:JSON.stringify({text:txt,language:'en',persona:'Default',attachments:atts})});
atts=[];renderPrev();
const reader=res.body.getReader();const dec=new TextDecoder();let raw='',buf='';
while(true){
const{done,value}=await reader.read();if(done)break;
buf+=dec.decode(value,{stream:true});
const parts=buf.split('\n\n');buf=parts.pop()||'';
for(const part of parts){
if(!part.startsWith('data: '))continue;
const data=part.slice(6);
if(data==='[DONE]')break;
if(data.startsWith('ERR:')){raw+='\n\n**Error:** '+data.slice(4);continue}
raw+=data.replace(/\\n/g,'\n');
const{thought,response}=parseThinking(raw);
aiMsg.innerHTML=renderThinking(thought,response);
msgs.scrollTop=msgs.scrollHeight;
}
}
// Final render
if(raw){const{thought,response}=parseThinking(raw);aiMsg.innerHTML=renderThinking(thought,response)}
}catch(e){aiMsg.innerHTML='<span style="color:var(--r)">Connection error: '+esc(e.message)+'</span>'}
streaming=false;if(inp.value.trim()&&ready)btn.disabled=false;
};
</script></body></html>
        """.trimIndent()
    }
}
