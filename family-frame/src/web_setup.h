#pragma once
// First-time setup page, shown while the frame runs its "FamilyFrame-Setup" hotspot.

#include <pgmspace.h>

static const char SETUP_HTML[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Family Frame setup</title>
<style>
:root{--bg:#f6f2ea;--card:#fff;--ink:#1f2633;--dim:#687083;--line:#e7e0d4;--accent:#d9780a;--on:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#121722;--card:#1b2231;--ink:#eef1f6;--dim:#9ba5b7;--line:#2b3447;--accent:#ffb03b;--on:#241500}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:460px;margin:auto;padding:24px 16px}
h1{font-size:24px;margin:0 0 4px}p{margin:6px 0}.dim{color:var(--dim);font-size:14px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin:16px 0}
label{display:block;margin:12px 0 4px;font-weight:600;font-size:14px}
input,select{font:16px system-ui,sans-serif;padding:10px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--ink);width:100%}
button{font:600 17px system-ui,sans-serif;border-radius:12px;border:0;background:var(--accent);color:var(--on);padding:14px;width:100%;margin-top:18px;cursor:pointer}
button.small{background:none;color:var(--accent);border:1px solid var(--line);font-size:14px;padding:8px;margin-top:8px}
</style></head><body><main>
<h1>Welcome to Family Frame</h1>
<p class="dim">Connect the frame to your home Wi-Fi. You can change everything else later from your phone.</p>
<div class="card" id="form">
 <label>Wi-Fi network</label>
 <select id="list"><option value="">Looking for networks...</option></select>
 <input id="ssid" placeholder="Or type the network name" style="margin-top:8px">
 <button class="small" id="rescan" type="button">Search again</button>
 <label>Wi-Fi password</label>
 <input id="pass" type="password" placeholder="Leave empty if there is none">
 <label>Time zone</label>
 <select id="tz"></select>
 <label>Names for greetings (optional)</label>
 <input id="names" placeholder="Linda and Bob">
 <button id="save">Connect</button>
</div>
<div class="card" id="done" hidden>
 <h1>All set!</h1>
 <p>The frame is restarting and joining your Wi-Fi. In a few seconds it shows a code to scan for adding photos.</p>
 <p class="dim">Reconnect this phone to your normal Wi-Fi.</p>
</div>
</main>
<script>
const $=s=>document.querySelector(s);
const TZ=[["Eastern (New York)","EST5EDT,M3.2.0,M11.1.0"],["Central (Chicago)","CST6CDT,M3.2.0,M11.1.0"],["Mountain (Denver)","MST7MDT,M3.2.0,M11.1.0"],["Arizona","MST7"],["Pacific (Los Angeles)","PST8PDT,M3.2.0,M11.1.0"],["Alaska","AKST9AKDT,M3.2.0,M11.1.0"],["Hawaii","HST10"],["Atlantic (Puerto Rico)","AST4"],["UK and Ireland","GMT0BST,M3.5.0/1,M10.5.0"],["Central Europe","CET-1CEST,M3.5.0,M10.5.0/3"],["Eastern Europe","EET-2EEST,M3.5.0/3,M10.5.0/4"],["India","IST-5:30"],["Japan","JST-9"],["Sydney / Melbourne","AEST-10AEDT,M10.1.0,M4.1.0/3"]];
$("#tz").innerHTML=TZ.map(t=>'<option value="'+t[1]+'">'+t[0]+"</option>").join("");
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
async function scan(){$("#list").innerHTML="<option value=''>Looking for networks...</option>";
 try{const j=await (await fetch("/api/scan")).json();
  $("#list").innerHTML="<option value=''>Choose your network</option>"+j.networks.sort((a,b)=>b.rssi-a.rssi).map(n=>"<option>"+esc(n.ssid)+"</option>").join("")}
 catch(e){$("#list").innerHTML="<option value=''>Couldn't search - type the name below</option>"}}
$("#list").onchange=()=>$("#ssid").value=$("#list").value;
$("#rescan").onclick=scan;
fetch("/api/settings").then(r=>r.json()).then(s=>{if(s.tz)$("#tz").value=s.tz;if(s.names)$("#names").value=s.names;if(s.wifiSsid)$("#ssid").value=s.wifiSsid}).catch(()=>{});
$("#save").onclick=async()=>{const ssid=$("#ssid").value.trim();if(!ssid)return alert("Choose or type your Wi-Fi network name.");
 const body={wifiSsid:ssid,wifiPass:$("#pass").value,tz:$("#tz").value,names:$("#names").value.trim()};
 try{const r=await fetch("/api/settings",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});
  if(!r.ok)throw 0;$("#form").hidden=true;$("#done").hidden=false}catch(e){alert("Couldn't save. Try again.")}};
scan();
</script></main></body></html>)HTML";
