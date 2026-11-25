#pragma once
// The main web page (phone or computer). Photos are resized in the browser to
// the frame's 320x240 screen before upload, so they load fast and always decode.

#include <pgmspace.h>

static const char INDEX_HTML[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Family Frame</title>
<style>
:root{--bg:#f6f2ea;--card:#fff;--ink:#1f2633;--dim:#687083;--line:#e7e0d4;--accent:#d9780a;--on-accent:#fff;--good:#1f9d55;--bad:#c93b3b;--soft:#fbf3e6}
@media (prefers-color-scheme:dark){:root{--bg:#121722;--card:#1b2231;--ink:#eef1f6;--dim:#9ba5b7;--line:#2b3447;--accent:#ffb03b;--on-accent:#241500;--good:#43c27b;--bad:#ff6b6b;--soft:#262016}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{padding:18px 16px 8px;max-width:760px;margin:auto;display:flex;align-items:center;gap:10px;flex-wrap:wrap}
header h1{font-size:22px;margin:0;flex:1;white-space:nowrap}
#status{font-size:13px;color:var(--dim)}
nav{position:sticky;top:0;z-index:5;background:var(--bg);border-bottom:1px solid var(--line)}
nav div{max-width:760px;margin:auto;display:flex;overflow-x:auto;padding:0 8px}
nav button{background:none;border:0;border-bottom:3px solid transparent;color:var(--dim);padding:12px 12px 9px;font:600 15px system-ui,sans-serif;white-space:nowrap;cursor:pointer}
nav button.on{color:var(--ink);border-color:var(--accent)}
main{max-width:760px;margin:auto;padding:12px 16px 60px}
section{display:none}section.on{display:block}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin:0 0 14px}
h2{font-size:18px;margin:0 0 10px}h3{font-size:15px;margin:16px 0 6px}
p{margin:6px 0}.dim{color:var(--dim);font-size:14px}
button,.btn{font:600 15px system-ui,sans-serif;border-radius:10px;border:1px solid var(--line);background:var(--soft);color:var(--ink);padding:9px 14px;cursor:pointer}
button.primary,.btn.primary{background:var(--accent);border-color:var(--accent);color:var(--on-accent)}
button.danger{color:var(--bad)}
button:disabled{opacity:.5}
.big{display:block;width:100%;text-align:center;font-size:19px;padding:18px;border-radius:14px}
input[type=text],input[type=password],input[type=number],input[type=time],input[type=url],select{font:16px system-ui,sans-serif;padding:9px 10px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--ink);width:100%}
input[type=range]{width:100%}
label.f{display:block;margin:10px 0 4px;font-weight:600;font-size:14px}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.row>*{flex:1}.row>.fit{flex:0 0 auto}
.check{display:flex;gap:10px;align-items:center;margin:10px 0;font-size:15px}.check input{width:20px;height:20px}
.choices{display:flex;gap:6px;flex-wrap:wrap;margin-top:12px}.choices label{flex:1;min-width:120px;border:1px solid var(--line);border-radius:10px;padding:8px;font-size:14px;display:flex;gap:6px;align-items:center}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}
.tile{border:1px solid var(--line);border-radius:12px;overflow:hidden;background:var(--bg)}
.tile img{display:block;width:100%;aspect-ratio:4/3;object-fit:cover;background:#0002}
.tile div{display:flex}.tile button{flex:1;border:0;border-radius:0;padding:8px;font-size:13px;background:none}
.bar{height:10px;border-radius:6px;background:var(--line);overflow:hidden;margin:12px 0 6px}.bar i{display:block;height:100%;width:0;background:var(--accent);transition:width .2s}
.feed{border:1px solid var(--line);border-radius:12px;padding:10px;margin:8px 0}
.feed .top{display:flex;gap:8px;align-items:center}.feed .top input[type=text]{font-weight:600}
.feed .url{font-size:12px;color:var(--dim);word-break:break-all;margin-top:6px}
.dot{width:10px;height:10px;border-radius:50%;flex:0 0 10px;background:var(--line)}.dot.ok{background:var(--good)}.dot.bad{background:var(--bad)}
.icon{flex:0 0 auto;padding:6px 10px}
.news h3{display:flex;gap:8px;align-items:center}.news ol{margin:4px 0 8px;padding-left:22px}.news li{margin:4px 0}.news small{color:var(--dim)}
.toast{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);background:var(--ink);color:var(--bg);padding:10px 16px;border-radius:12px;font-size:15px;opacity:0;transition:opacity .25s;pointer-events:none;max-width:92vw}
.toast.on{opacity:1}
.answer{background:var(--soft);border-radius:12px;padding:12px;margin-top:10px;white-space:pre-wrap}
dl{display:grid;grid-template-columns:auto 1fr;gap:4px 14px;margin:0;font-size:15px}dt{color:var(--dim)}dd{margin:0}
ol.steps{padding-left:20px}ol.steps li{margin:6px 0}
a{color:var(--accent)}
</style></head><body>
<header><h1>Family Frame</h1><span id="status">Connecting...</span></header>
<nav><div>
<button data-tab="photos" class="on">Photos</button><button data-tab="news">News</button><button data-tab="settings">Settings</button><button data-tab="assistant">Assistant</button><button data-tab="frame">Frame</button>
</div></nav>
<main>

<section id="photos" class="on">
 <div class="card">
  <label class="btn primary big">Add photos<input id="pick" type="file" accept="image/*" multiple hidden></label>
  <div class="choices">
   <label><input type="radio" name="fit" value="smart" checked> Smart</label>
   <label><input type="radio" name="fit" value="fit"> Whole photo</label>
   <label><input type="radio" name="fit" value="fill"> Fill screen</label>
  </div>
  <p class="dim">Smart fills the screen with landscape photos and shows tall photos whole, over a soft blurred background. Photos are shrunk on your phone first, so uploads are quick.</p>
  <div id="prog" hidden><div class="bar"><i id="bar"></i></div><p id="progText" class="dim"></p></div>
 </div>
 <div class="card">
  <div class="row"><h2>On the frame <span id="pcount" class="dim"></span></h2></div>
  <div id="grid" class="grid"></div>
  <p><button id="more" hidden>Show more</button></p>
 </div>
</section>

<section id="news">
 <div class="card">
  <div class="row"><h2>Latest headlines</h2><span class="fit"><button id="refresh">Refresh</button> <button id="briefNow" class="primary">Play briefing</button></span></div>
  <div id="newsList" class="news"><p class="dim">Loading...</p></div>
 </div>
 <div class="card">
  <h2>Your news feeds</h2>
  <p class="dim">Feeds appear as cards between photos and are read out at briefing times. Up to 12.</p>
  <div id="feeds"></div>
  <h3>Follow a topic</h3>
  <div class="row"><input id="topic" type="text" placeholder="e.g. Kansas City Chiefs, gardening, Marvel Rivals"><button class="fit" id="addTopic">Add</button></div>
  <p class="dim">Topics come from Google News, which gathers stories from thousands of sites.</p>
  <h3>Add a source</h3>
  <div class="row"><select id="preset"></select><button class="fit" id="addPreset">Add</button></div>
  <details><summary class="dim">Add any RSS or Atom link</summary>
   <div class="row" style="margin-top:8px"><input id="customName" type="text" placeholder="Name"><input id="customUrl" type="url" placeholder="https://..."><button class="fit" id="addCustom">Add</button></div>
  </details>
  <p><button class="primary" id="saveFeeds">Save feeds</button></p>
 </div>
</section>

<section id="settings">
 <div class="card">
  <h2>Home</h2>
  <label class="f">Names for greetings</label><input type="text" data-k="names" placeholder="Linda and Bob">
  <label class="f">Time zone</label><select data-k="tz" id="tz"></select>
  <label class="f">Town for weather</label>
  <div class="row"><input type="text" id="placeSearch" placeholder="e.g. Springfield, MO"><button class="fit" id="findPlace">Find</button></div>
  <p class="dim" id="placeNow"></p>
  <div id="placeResults"></div>
  <label class="f">Temperature</label><select data-k="fahrenheit" data-type="bool"><option value="true">Fahrenheit</option><option value="false">Celsius</option></select>
 </div>
 <div class="card">
  <h2>Slideshow</h2>
  <label class="f">Seconds per photo</label><input type="number" min="5" max="3600" data-k="slideSec" data-type="num">
  <label class="f">Show a news or weather card after every</label>
  <select data-k="newsEvery" data-type="num"><option value="0">Never</option><option>2</option><option>3</option><option>5</option><option>8</option><option>12</option><option>20</option></select>
  <p class="dim">photos</p>
  <label class="check"><input type="checkbox" data-k="shuffle"> Shuffle photos</label>
  <label class="check"><input type="checkbox" data-k="fade"> Fade between photos</label>
  <label class="check"><input type="checkbox" data-k="clock"> Show the clock (and weather) on photos</label>
  <label class="check"><input type="checkbox" data-k="clock24"> 24-hour clock</label>
  <label class="f">Clock corner</label>
  <select data-k="clockCorner" data-type="num"><option value="3">Bottom right</option><option value="2">Bottom left</option><option value="1">Top right</option><option value="0">Top left</option></select>
  <label class="f">Brightness <span data-show="brightness"></span>%</label><input type="range" min="5" max="100" data-k="brightness" data-type="num">
 </div>
 <div class="card">
  <h2>Night time</h2>
  <label class="check"><input type="checkbox" data-k="nightOn"> Dim the screen at night</label>
  <div class="row"><div><label class="f">From</label><input type="time" data-k="nightStart"></div><div><label class="f">Until</label><input type="time" data-k="nightEnd"></div></div>
  <label class="f">Night brightness <span data-show="nightBright"></span>% (0 = off; a tap wakes it)</label><input type="range" min="0" max="60" data-k="nightBright" data-type="num">
 </div>
 <div class="card">
  <h2>Briefings</h2>
  <p class="dim">At these times the frame chimes, greets everyone, gives the weather and reads the top headlines from each feed.</p>
  <div id="briefs"></div>
  <label class="check"><input type="checkbox" data-k="briefSpeak"> Read briefings aloud</label>
  <label class="f">Check for new headlines every</label>
  <select data-k="newsRefreshMin" data-type="num"><option value="30">30 minutes</option><option value="60">hour</option><option value="120">2 hours</option><option value="240">4 hours</option></select>
 </div>
 <div class="card">
  <h2>Screen</h2>
  <label class="check"><input type="checkbox" data-k="invert"> Invert colors (if photos look like negatives)</label>
  <label class="check"><input type="checkbox" data-k="swapRB"> Swap red and blue</label>
  <p><button id="touchSetup">Redo touch setup on the frame</button></p>
 </div>
 <button class="primary big" id="saveSettings">Save settings</button>
</section>

<section id="assistant">
 <div class="card">
  <h2>Voice assistant</h2>
  <p>Tap <b>Ask</b> on the frame (or hold the screen), wait for the beep, and ask something like <i>"What's in the news today?"</i>, <i>"Any good Amazon deals?"</i> or <i>"How tall is the Eiffel Tower?"</i>. The answer appears on screen and is read aloud.</p>
  <p class="dim">It knows the frame's latest headlines and weather. It needs the microphone wired up (see the wiring guide) and a free AI key.</p>
  <h3>Get a free key (2 minutes)</h3>
  <ol class="steps">
   <li>Open <a href="https://console.groq.com/keys" target="_blank" rel="noopener">console.groq.com/keys</a> and sign in with Google or email.</li>
   <li>Click <b>Create API Key</b>, name it "Family Frame", and copy it.</li>
   <li>Paste it below and press Save.</li>
  </ol>
  <label class="f">AI key</label><input type="password" data-k="aiKey" data-secret autocomplete="off" placeholder="gsk_...">
  <label class="check"><input type="checkbox" data-k="clearAiKey"> Remove the saved key</label>
 </div>
 <div class="card">
  <h2>Microphone and speaker</h2>
  <label class="check"><input type="checkbox" data-k="micOn"> Microphone is connected</label>
  <label class="check"><input type="checkbox" data-k="speakerOn"> Speaker is connected</label>
  <label class="check"><input type="checkbox" data-k="speakAnswers"> Read answers aloud</label>
  <label class="f">Volume <span data-show="volume"></span>%</label><input type="range" min="0" max="100" data-k="volume" data-type="num">
  <label class="f">Mic gain <span data-show="micGain"></span></label><input type="range" min="1" max="32" data-k="micGain" data-type="num">
  <p class="row"><button id="testSpeaker">Test speaker</button><button id="testMic">Test microphone</button></p>
  <p class="dim">Watch the frame for the test. The mic test records 4 seconds and plays it back.</p>
  <details><summary class="dim">Advanced: use a different AI service</summary>
   <p class="dim">Any OpenAI-compatible service works. Groq is free; OpenAI's address is https://api.openai.com/v1.</p>
   <label class="f">Service address</label><input type="url" data-k="aiUrl">
   <label class="f">Chat model</label><input type="text" data-k="aiModel">
   <label class="f">Speech-to-text model</label><input type="text" data-k="sttModel">
   <label class="f">Language code</label><input type="text" data-k="lang" placeholder="en">
  </details>
  <p><button class="primary" id="saveAssistant">Save</button></p>
 </div>
 <div class="card">
  <h2>Try it by typing</h2>
  <div class="row"><input type="text" id="question" placeholder="What's the weather today?"><button class="fit primary" id="ask">Ask</button></div>
  <div id="answer" class="answer" hidden></div>
 </div>
</section>

<section id="frame">
 <div class="card"><h2>Status</h2><dl id="statusList"></dl></div>
 <div class="card">
  <h2>Wi-Fi</h2>
  <label class="f">Network</label>
  <div class="row"><input type="text" id="wifiSsid" placeholder="Network name"><button class="fit" id="scan">Scan</button></div>
  <select id="networks" hidden></select>
  <label class="f">Password</label><input type="password" id="wifiPass" placeholder="(unchanged)">
  <p><button class="primary" id="saveWifi">Save and restart</button></p>
 </div>
 <div class="card">
  <h2>Page password</h2>
  <p class="dim">Optional. Anyone on your Wi-Fi can open this page unless you set one. The user name is <b>family</b>.</p>
  <input type="password" id="webPass" placeholder="New password">
  <label class="check"><input type="checkbox" id="clearWebPass"> Remove the password</label>
  <p><button id="savePass">Save password</button></p>
 </div>
 <div class="card">
  <h2>Update firmware</h2>
  <p class="dim">Choose the file ending in <b>-update.bin</b>. Photos and settings are kept.</p>
  <input type="file" id="fw" accept=".bin">
  <div class="bar"><i id="fwBar"></i></div>
  <p><button id="flash">Update</button> <button id="reboot">Restart frame</button></p>
 </div>
</section>
</main>
<div class="toast" id="toast"></div>
<script>
const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
const TZ=[["Eastern (New York)","EST5EDT,M3.2.0,M11.1.0"],["Central (Chicago)","CST6CDT,M3.2.0,M11.1.0"],["Mountain (Denver)","MST7MDT,M3.2.0,M11.1.0"],["Arizona","MST7"],["Pacific (Los Angeles)","PST8PDT,M3.2.0,M11.1.0"],["Alaska","AKST9AKDT,M3.2.0,M11.1.0"],["Hawaii","HST10"],["Atlantic (Puerto Rico)","AST4"],["UK and Ireland","GMT0BST,M3.5.0/1,M10.5.0"],["Central Europe","CET-1CEST,M3.5.0,M10.5.0/3"],["Eastern Europe","EET-2EEST,M3.5.0/3,M10.5.0/4"],["India","IST-5:30"],["Japan","JST-9"],["Sydney / Melbourne","AEST-10AEDT,M10.1.0,M4.1.0/3"]];
const GN=q=>"https://news.google.com/rss/search?q="+encodeURIComponent(q)+"&hl=en-US&gl=US&ceid=US:en";
const GT=t=>"https://news.google.com/rss/headlines/section/topic/"+t+"?hl=en-US&gl=US&ceid=US:en";
const PRESETS=[
 ["General news",[["Top Stories","https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"],["U.S. News",GT("NATION")],["World News",GT("WORLD")],["Business",GT("BUSINESS")],["Science",GT("SCIENCE")],["Health",GT("HEALTH")],["Sports",GT("SPORTS")],["Entertainment",GT("ENTERTAINMENT")],["NPR News","https://feeds.npr.org/1001/rss.xml"],["BBC News","https://feeds.bbci.co.uk/news/rss.xml"],["AP News",GN("site:apnews.com when:1d")]]],
 ["Washington Post",[["Washington Post","https://feeds.washingtonpost.com/rss/national"],["WaPo World","https://feeds.washingtonpost.com/rss/world"],["WaPo Politics","https://feeds.washingtonpost.com/rss/politics"],["WaPo (via Google News)",GN("site:washingtonpost.com when:1d")]]],
 ["Tech",[["Tech News",GT("TECHNOLOGY")],["The Verge","https://www.theverge.com/rss/index.xml"],["Ars Technica","https://feeds.arstechnica.com/arstechnica/index"],["TechCrunch","https://techcrunch.com/feed/"],["Engadget","https://www.engadget.com/rss.xml"]]],
 ["Deals",[["Amazon Deals","https://slickdeals.net/newsearch.php?q=amazon&searcharea=deals&searchin=first&rss=1"],["Hot Deals (Slickdeals)","https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=deals&searchin=first&rss=1"],["Amazon deals in the news",GN("Amazon deals when:2d")]]],
 ["Marvel and games",[["Contest of Champions",GN('"Contest of Champions" when:14d')],["Marvel Movies",GN("Marvel Studios movie when:7d")],["Marvel TV shows",GN("Marvel series Disney+ when:7d")]]],
 ["Social media (news coverage)",[["TikTok trends",GN("TikTok trend when:2d")],["Viral on social media",GN("viral social media when:1d")],["Instagram news",GN("Instagram when:3d")]]]
];
let S={},feeds=[],photoNames=[],shown=0;

function toast(msg,ms=2600){const t=$("#toast");t.textContent=msg;t.classList.add("on");clearTimeout(t._h);t._h=setTimeout(()=>t.classList.remove("on"),ms)}
async function api(path,opt={}){
 const r=await fetch(path,opt);let j={};try{j=await r.json()}catch(e){}
 if(!r.ok)throw new Error(j.error||("Error "+r.status));return j}
const post=(path,body)=>api(path,{method:"POST",headers:{"Content-Type":"application/json"},body:body===undefined?"{}":JSON.stringify(body)});

$$("nav button").forEach(b=>b.onclick=()=>{
 $$("nav button").forEach(x=>x.classList.toggle("on",x===b));
 $$("section").forEach(s=>s.classList.toggle("on",s.id===b.dataset.tab));
 if(b.dataset.tab==="news")loadNews();if(b.dataset.tab==="frame")loadStatus(true);
 try{localStorage.setItem("tab",b.dataset.tab)}catch(e){}});

/* ---------- photos ---------- */
function loadImage(file){
 if(window.createImageBitmap)return createImageBitmap(file,{imageOrientation:"from-image"}).catch(()=>viaImg(file));
 return viaImg(file)}
function viaImg(file){return new Promise((ok,bad)=>{const i=new Image();i.onload=()=>ok(i);i.onerror=()=>bad(new Error("Can't open "+file.name));i.src=URL.createObjectURL(file)})}
// Halve the size step by step for a sharp, smooth small photo.
function shrink(img,w,h){
 let src=img,sw=img.width,sh=img.height;
 while(sw/2>=w*1.2&&sh/2>=h*1.2){const c=document.createElement("canvas");c.width=Math.round(sw/2);c.height=Math.round(sh/2);
  const g=c.getContext("2d");g.imageSmoothingQuality="high";g.drawImage(src,0,0,c.width,c.height);src=c;sw=c.width;sh=c.height}
 return src}
async function frameJpeg(file,mode){
 const img=await loadImage(file),W=320,H=240,iw=img.width,ih=img.height;
 const c=document.createElement("canvas");c.width=W;c.height=H;const g=c.getContext("2d");
 g.imageSmoothingEnabled=true;g.imageSmoothingQuality="high";
 let m=mode;if(m==="smart")m=Math.abs(Math.log((iw/ih)/(W/H)))<0.3?"fill":"fit";
 if(m==="fill"){const s=Math.max(W/iw,H/ih),dw=iw*s,dh=ih*s;g.drawImage(shrink(img,dw,dh),(W-dw)/2,(H-dh)/2,dw,dh)}
 else{const t=document.createElement("canvas");t.width=32;t.height=24;const s0=Math.max(32/iw,24/ih);
  t.getContext("2d").drawImage(img,(32-iw*s0)/2,(24-ih*s0)/2,iw*s0,ih*s0);
  g.drawImage(t,0,0,W,H);g.fillStyle="rgba(0,0,0,.35)";g.fillRect(0,0,W,H);
  const s=Math.min(W/iw,H/ih),dw=iw*s,dh=ih*s;g.drawImage(shrink(img,dw,dh),(W-dw)/2,(H-dh)/2,dw,dh)}
 return new Promise(ok=>c.toBlob(ok,"image/jpeg",0.88))}
$("#pick").onchange=async e=>{
 const files=[...e.target.files];e.target.value="";if(!files.length)return;
 const mode=document.querySelector("input[name=fit]:checked").value;
 $("#prog").hidden=false;let done=0,failed=0;
 for(const [i,f] of files.entries()){
  $("#progText").textContent="Adding photo "+(i+1)+" of "+files.length+"...";
  try{const blob=await frameJpeg(f,mode);const fd=new FormData();
   const base=(f.name||"photo").replace(/\.[^.]*$/,"").replace(/[^A-Za-z0-9_-]/g,"").slice(0,24)||"photo";
   fd.append("file",blob,base+"-"+Date.now().toString(36)+".jpg");
   await api("/api/upload",{method:"POST",body:fd});done++}catch(err){failed++;console.log(err)}
  $("#bar").style.width=Math.round((i+1)*100/files.length)+"%"}
 $("#progText").textContent=done+" added"+(failed?", "+failed+" couldn't be added":"")+". The newest one is on the frame now.";
 setTimeout(()=>{$("#prog").hidden=true;$("#bar").style.width="0"},6000);loadPhotos()};
async function loadPhotos(){
 try{const j=await api("/api/photos");photoNames=j.names.reverse();$("#pcount").textContent="("+j.count+")";$("#grid").innerHTML="";shown=0;morePhotos()}
 catch(e){$("#grid").innerHTML='<p class="dim">'+e.message+"</p>"}}
function morePhotos(){
 const next=photoNames.slice(shown,shown+24);shown+=next.length;
 for(const n of next){const d=document.createElement("div");d.className="tile";
  const q=encodeURIComponent(n);
  d.innerHTML='<img loading="lazy" alt=""><div><button>Show</button><button class="danger">Delete</button></div>';
  d.querySelector("img").src="/photo?name="+q;
  const [show,del]=d.querySelectorAll("button");
  show.onclick=()=>post("/api/show?name="+q).then(()=>toast("Showing it on the frame")).catch(e=>toast(e.message));
  del.onclick=async()=>{if(!confirm("Delete this photo from the frame?"))return;
   try{await post("/api/delete?name="+q);d.remove();toast("Deleted")}catch(e){toast(e.message)}};
  $("#grid").appendChild(d)}
 if(!photoNames.length)$("#grid").innerHTML='<p class="dim">No photos yet. Tap <b>Add photos</b> above.</p>';
 $("#more").hidden=shown>=photoNames.length}
$("#more").onclick=morePhotos;

/* ---------- news ---------- */
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
function statusWord(f){if(!f.on)return"off";if(f.status===200)return f.items.length?"ok":"empty";if(!f.status)return"waiting";return"error "+f.status}
async function loadNews(){
 try{const j=await api("/api/news");let h="";
  if(j.busy)h+='<p class="dim">Fetching headlines now...</p>';
  for(const f of j.feeds){const st=statusWord(f),cls=st==="ok"?"ok":(st.startsWith("error")||st==="empty")?"bad":"";
   h+='<h3><span class="dot '+cls+'"></span>'+esc(f.name)+(st==="ok"?"":' <small>('+st+")</small>")+"</h3>";
   if(f.items.length)h+="<ol>"+f.items.map(i=>"<li>"+esc(i.t)+(i.s?" <small>"+esc(i.s)+"</small>":"")+"</li>").join("")+"</ol>"}
  $("#newsList").innerHTML=h||'<p class="dim">No feeds yet.</p>';
  if(j.busy)setTimeout(loadNews,4000)}catch(e){$("#newsList").innerHTML='<p class="dim">'+e.message+"</p>"}}
$("#refresh").onclick=()=>post("/api/refresh").then(()=>{toast("Fetching new headlines...");setTimeout(loadNews,1500)});
$("#briefNow").onclick=()=>post("/api/briefing").then(()=>toast("Starting the briefing on the frame"));
function drawFeeds(){
 const box=$("#feeds");box.innerHTML="";
 feeds.forEach((f,i)=>{const d=document.createElement("div");d.className="feed";
  d.innerHTML='<div class="top"><input type="checkbox" title="Show this feed"><input type="text"><button class="icon" title="Move up">&uarr;</button><button class="icon danger" title="Remove">&times;</button></div><div class="url"></div>';
  const [on,name]=d.querySelectorAll("input"),[up,rm]=d.querySelectorAll("button");
  on.checked=f.on;name.value=f.name;d.querySelector(".url").textContent=f.url;
  on.onchange=()=>f.on=on.checked;name.oninput=()=>f.name=name.value;
  up.disabled=i===0;up.onclick=()=>{feeds.splice(i-1,0,feeds.splice(i,1)[0]);drawFeeds()};
  rm.onclick=()=>{feeds.splice(i,1);drawFeeds()};box.appendChild(d)})}
function addFeed(name,url){if(feeds.length>=12)return toast("That's the most feeds the frame can hold (12).");
 feeds.push({name,url,on:true});drawFeeds();toast("Added "+name+". Press Save feeds.")}
$("#addTopic").onclick=()=>{const t=$("#topic").value.trim();if(!t)return;addFeed(t.replace(/\b\w/g,c=>c.toUpperCase()).slice(0,30),GN(t+" when:7d"));$("#topic").value=""};
$("#addPreset").onclick=()=>{const v=$("#preset").value.split("|");addFeed(v[0],v[1])};
$("#addCustom").onclick=()=>{const n=$("#customName").value.trim()||"News",u=$("#customUrl").value.trim();if(!/^https?:\/\//.test(u))return toast("The link should start with https://");addFeed(n,u)};
$("#saveFeeds").onclick=()=>post("/api/settings",{feeds}).then(()=>{toast("Saved. Fetching headlines...");setTimeout(loadNews,3000)}).catch(e=>toast(e.message));

/* ---------- settings ---------- */
function fill(){
 $$("[data-k]").forEach(el=>{const k=el.dataset.k;if(el.dataset.secret!==undefined){el.value="";el.placeholder=S[k+"Set"]?"(saved - type to replace)":el.placeholder;return}
  if(!(k in S))return;if(el.type==="checkbox")el.checked=!!S[k];else el.value=String(S[k])});
 $$("[data-show]").forEach(sp=>{const el=$('[data-k="'+sp.dataset.show+'"]');const upd=()=>sp.textContent=el.value;el.oninput=upd;upd()});
 $("#placeNow").textContent=S.place?"Now: "+S.place:"No town set yet, so the frame shows no weather.";
 $("#briefs").innerHTML="";(S.briefings||[]).forEach((b,i)=>{const d=document.createElement("div");d.className="row";
  d.innerHTML='<label class="check fit"><input type="checkbox"> '+["Morning","Midday","Evening"][i]+'</label><input type="time">';
  const [on,at]=d.querySelectorAll("input");on.checked=b.on;at.value=b.at;on.onchange=()=>b.on=on.checked;at.onchange=()=>b.at=at.value;$("#briefs").appendChild(d)});
 feeds=(S.feeds||[]).map(f=>({...f}));drawFeeds();$("#wifiSsid").value=S.wifiSsid||""}
function collect(section){const o={};
 $$("#"+section+" [data-k]").forEach(el=>{const k=el.dataset.k;
  if(el.type==="checkbox")o[k]=el.checked;else if(el.dataset.type==="num")o[k]=Number(el.value);
  else if(el.dataset.type==="bool")o[k]=el.value==="true";else o[k]=el.value});return o}
async function loadSettings(){S=await api("/api/settings");fill()}
$("#saveSettings").onclick=async()=>{const o=collect("settings");o.briefings=S.briefings;if(S._place)Object.assign(o,S._place);
 try{await post("/api/settings",o);toast("Saved");loadSettings()}catch(e){toast(e.message)}};
$("#findPlace").onclick=async()=>{const q=$("#placeSearch").value.trim();if(!q)return;
 try{const r=await fetch("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name="+encodeURIComponent(q.split(",")[0]));const j=await r.json();
  const box=$("#placeResults");box.innerHTML="";if(!j.results)return box.textContent="No match. Try just the town name.";
  for(const p of j.results){const label=[p.name,p.admin1,p.country_code].filter(Boolean).join(", ");const b=document.createElement("button");
   b.textContent=label;b.style.margin="4px 4px 0 0";b.onclick=()=>{S._place={lat:p.latitude,lon:p.longitude,place:p.name};$("#placeNow").textContent="Chosen: "+label+" (press Save settings)";box.innerHTML=""};box.appendChild(b)}}
 catch(e){toast("Couldn't search. Is this phone online?")}};
$("#touchSetup").onclick=()=>post("/api/touch-setup").then(()=>toast("Follow the steps on the frame"));

/* ---------- assistant ---------- */
$("#saveAssistant").onclick=async()=>{try{await post("/api/settings",collect("assistant"));toast("Saved");loadSettings()}catch(e){toast(e.message)}};
$("#testSpeaker").onclick=()=>post("/api/test/speaker").then(()=>toast("Listen to the frame"));
$("#testMic").onclick=()=>post("/api/test/mic").then(()=>toast("Speak toward the frame now"));
$("#ask").onclick=async()=>{const q=$("#question").value.trim();if(!q)return;const a=$("#answer");a.hidden=false;a.textContent="Thinking...";
 try{const j=await post("/api/ask",{text:q});a.textContent=j.answer}catch(e){a.textContent=e.message}};

/* ---------- frame ---------- */
async function loadStatus(full){
 try{const s=await api("/api/status");
  $("#status").textContent=(s.online?"Online":"Offline")+" - "+s.photos+" photos"+(s.time?" - "+s.time:"");
  if(!full)return;const rows=[["Address","http://"+s.ip+"/ or http://"+s.hostname+".local/"],["Wi-Fi",s.ssid+(s.rssi?" ("+(s.rssi>-60?"strong":s.rssi>-72?"good":"weak")+" signal)":"")],["Photos",s.photos],["SD card",s.sdUsedMB+" MB used of "+s.sdTotalMB+" MB"],["Weather",s.weather||"not set"],["Firmware",s.version],["Free memory",s.heapKB+" KB"],["Running for",Math.round(s.uptimeMin/60)+" hours"]];
  $("#statusList").innerHTML=rows.map(r=>"<dt>"+r[0]+"</dt><dd>"+esc(r[1])+"</dd>").join("")}catch(e){$("#status").textContent="Can't reach the frame"}}
$("#scan").onclick=async()=>{toast("Scanning...");try{const j=await api("/api/scan");const sel=$("#networks");
 sel.innerHTML="<option value=''>Choose a network</option>"+j.networks.map(n=>"<option>"+esc(n.ssid)+"</option>").join("");sel.hidden=false;sel.onchange=()=>$("#wifiSsid").value=sel.value}catch(e){toast(e.message)}};
$("#saveWifi").onclick=async()=>{const ssid=$("#wifiSsid").value.trim();if(!ssid)return toast("Enter the network name");
 if(!confirm("The frame will restart and join "+ssid+". Continue?"))return;
 try{await post("/api/settings",{wifiSsid:ssid,wifiPass:$("#wifiPass").value});toast("Saved. The frame is restarting.",6000)}catch(e){toast(e.message)}};
$("#savePass").onclick=async()=>{const p=$("#webPass").value,clear=$("#clearWebPass").checked;if(!p&&!clear)return;
 try{await post("/api/settings",clear?{clearWebPass:true}:{webPass:p});toast("Saved")}catch(e){toast(e.message)}};
$("#reboot").onclick=()=>confirm("Restart the frame?")&&post("/api/reboot").then(()=>toast("Restarting..."));
$("#flash").onclick=()=>{const f=$("#fw").files[0];if(!f)return toast("Choose the update file first");
 const x=new XMLHttpRequest(),fd=new FormData();fd.append("firmware",f,f.name);
 x.upload.onprogress=e=>$("#fwBar").style.width=Math.round(e.loaded*100/e.total)+"%";
 x.onload=()=>{let m="";try{m=JSON.parse(x.responseText).error}catch(e){}toast(x.status===200?"Updated! The frame is restarting.":(m||"Update failed"),8000)};
 x.onerror=()=>toast("Upload failed");x.open("POST","/api/update");x.send(fd)};

/* ---------- start ---------- */
$("#tz").innerHTML=TZ.map(t=>'<option value="'+t[1]+'">'+t[0]+"</option>").join("");
$("#preset").innerHTML=PRESETS.map(g=>'<optgroup label="'+g[0]+'">'+g[1].map(p=>'<option value="'+esc(p[0]+"|"+p[1])+'">'+esc(p[0])+"</option>").join("")+"</optgroup>").join("");
loadSettings().then(()=>{if(S.tz&&!TZ.some(t=>t[1]===S.tz))$("#tz").insertAdjacentHTML("beforeend",'<option value="'+esc(S.tz)+'" selected>'+esc(S.tz)+"</option>"),$("#tz").value=S.tz}).catch(e=>toast(e.message));
loadPhotos();loadStatus();setInterval(()=>loadStatus(false),30000);
try{const t=localStorage.getItem("tab");if(t)document.querySelector('nav button[data-tab="'+t+'"]').click()}catch(e){}
</script></body></html>)HTML";
