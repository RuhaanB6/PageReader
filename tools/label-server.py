#!/usr/bin/env python3
"""A tiny local labelling tool that runs in the browser.

labelImg is PyQt5-based, unmaintained, and segfaults on click under recent
macOS/Python. This does the same job with no GUI toolkit at all: a local HTTP
server serves the frames, the browser draws the boxes, and labels are written
straight back to disk as YOLO .txt files. Nothing leaves the machine.

Usage:
  tools/label-server.py <folder-of-jpgs> [--port 8765]
"""
import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

ROOT = None

PAGE = r"""<!doctype html><html><head><meta charset="utf-8"><title>Label pages</title>
<style>
 :root{--bg:#12151a;--fg:#e8edf2;--mut:#8b97a5;--line:#2a323c;--ok:#3fb950;--warn:#d29922}
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
      height:100vh;display:flex;flex-direction:column;overflow:hidden}
 header{display:flex;align-items:center;gap:16px;padding:10px 16px;border-bottom:1px solid var(--line);flex:none}
 h1{font-size:14px;margin:0;font-weight:600;letter-spacing:.02em}
 .count{font-variant-numeric:tabular-nums;color:var(--mut)}
 .bar{flex:1;height:6px;background:var(--line);border-radius:3px;overflow:hidden}
 .bar i{display:block;height:100%;background:var(--ok);width:0%}
 #stage{flex:1;position:relative;display:flex;align-items:center;justify-content:center;
        overflow:hidden;background:#0b0e12}
 #wrap{position:relative;line-height:0}
 #img{max-width:100%;max-height:100%;display:block;user-select:none;-webkit-user-drag:none}
 #ov{position:absolute;inset:0;cursor:crosshair}
 footer{display:flex;align-items:center;gap:10px;padding:10px 16px;border-top:1px solid var(--line);flex:none;flex-wrap:wrap}
 button{background:#1d232c;color:var(--fg);border:1px solid var(--line);border-radius:6px;
        padding:7px 13px;font-size:13px;cursor:pointer}
 button:hover{background:#252d38}
 button.primary{background:#1f6feb;border-color:#1f6feb}
 kbd{background:#1d232c;border:1px solid var(--line);border-radius:4px;padding:1px 5px;font-size:11px;font-family:ui-monospace,monospace}
 .name{font-family:ui-monospace,monospace;font-size:12px;color:var(--mut)}
 .hint{color:var(--mut);font-size:12px}
 .saved{color:var(--ok)}
 .empty{color:var(--warn)}
</style></head><body>
<header>
  <h1>Label pages</h1>
  <span class="count" id="pos">–</span>
  <div class="bar"><i id="prog"></i></div>
  <span class="count" id="done">–</span>
</header>
<div id="stage"><div id="wrap"><img id="img" alt=""><canvas id="ov"></canvas></div></div>
<footer>
  <button id="prev">← Prev</button>
  <button id="next" class="primary">Next →</button>
  <button id="clear">Clear box</button>
  <span class="name" id="name"></span>
  <span id="state"></span>
  <span class="hint">Drag to draw · <kbd>←</kbd><kbd>→</kbd> move · <kbd>c</kbd> clear · saves automatically</span>
</footer>
<script>
const img=document.getElementById('img'), ov=document.getElementById('ov'), ctx=ov.getContext('2d');
let files=[], i=0, box=null, drag=null;

function draw(){
  ov.width=img.clientWidth; ov.height=img.clientHeight;
  ov.style.width=img.clientWidth+'px'; ov.style.height=img.clientHeight+'px';
  ctx.clearRect(0,0,ov.width,ov.height);
  const b = drag || box;
  if(!b) return;
  ctx.strokeStyle='#3fb950'; ctx.lineWidth=2;
  ctx.strokeRect(b.x*ov.width, b.y*ov.height, b.w*ov.width, b.h*ov.height);
  ctx.fillStyle='rgba(63,185,80,.12)';
  ctx.fillRect(b.x*ov.width, b.y*ov.height, b.w*ov.width, b.h*ov.height);
}

function render(){
  const f=files[i];
  document.getElementById('pos').textContent=(i+1)+' / '+files.length;
  document.getElementById('name').textContent=f.name;
  const n=files.filter(x=>x.box).length;
  document.getElementById('done').textContent=n+' boxed';
  document.getElementById('prog').style.width=(100*n/files.length)+'%';
  const st=document.getElementById('state');
  st.textContent = f.box ? 'saved' : 'no box yet';
  st.className = f.box ? 'saved' : 'empty';
  box = f.box; drag=null;
  img.onload=draw;
  img.src='/img/'+encodeURIComponent(f.name)+'?v='+Date.now();
}

async function save(){
  const f=files[i]; f.box=box;
  await fetch('/api/label/'+encodeURIComponent(f.name),{method:'POST',
    headers:{'Content-Type':'application/json'}, body:JSON.stringify(box)});
  render();
}

function pos(e){
  const r=ov.getBoundingClientRect();
  return {x:Math.min(1,Math.max(0,(e.clientX-r.left)/r.width)),
          y:Math.min(1,Math.max(0,(e.clientY-r.top)/r.height))};
}
ov.addEventListener('pointerdown',e=>{ov.setPointerCapture(e.pointerId);
  const p=pos(e); drag={x0:p.x,y0:p.y,x:p.x,y:p.y,w:0,h:0}; draw();});
ov.addEventListener('pointermove',e=>{ if(!drag) return; const p=pos(e);
  drag.x=Math.min(p.x,drag.x0); drag.y=Math.min(p.y,drag.y0);
  drag.w=Math.abs(p.x-drag.x0); drag.h=Math.abs(p.y-drag.y0); draw();});
ov.addEventListener('pointerup',()=>{ if(!drag) return;
  if(drag.w>0.01 && drag.h>0.01) box={x:drag.x,y:drag.y,w:drag.w,h:drag.h};
  drag=null; save();});

document.getElementById('next').onclick=()=>{ if(i<files.length-1){i++;render();} };
document.getElementById('prev').onclick=()=>{ if(i>0){i--;render();} };
document.getElementById('clear').onclick=()=>{ box=null; save(); };
window.addEventListener('keydown',e=>{
  if(e.key==='ArrowRight'||e.key==='d'){ if(i<files.length-1){i++;render();} }
  if(e.key==='ArrowLeft'||e.key==='a'){ if(i>0){i--;render();} }
  if(e.key==='c'){ box=null; save(); }
});
window.addEventListener('resize',draw);

fetch('/api/images').then(r=>r.json()).then(d=>{files=d; if(files.length){
  const first=files.findIndex(f=>!f.box); i=first<0?0:first; render();}});
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/?"):
            return self._send(200, PAGE.encode(), "text/html; charset=utf-8")

        if self.path == "/api/images":
            out = []
            for p in sorted(ROOT.glob("*.jpg")):
                lbl = ROOT / (p.stem + ".txt")
                box = None
                if lbl.exists() and lbl.read_text().strip():
                    _, cx, cy, w, h = (float(v) for v in lbl.read_text().split()[:5])
                    box = {"x": cx - w / 2, "y": cy - h / 2, "w": w, "h": h}
                out.append({"name": p.name, "box": box})
            return self._send(200, json.dumps(out).encode())

        m = re.match(r"^/img/(.+)$", self.path.split("?")[0])
        if m:
            f = ROOT / unquote(m.group(1))
            if f.is_file() and f.parent == ROOT:
                return self._send(200, f.read_bytes(), "image/jpeg")
        self._send(404, b"{}")

    def do_POST(self):
        m = re.match(r"^/api/label/(.+)$", self.path)
        if not m:
            return self._send(404, b"{}")
        name = unquote(m.group(1))
        f = ROOT / name
        if not (f.is_file() and f.parent == ROOT):
            return self._send(404, b"{}")

        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        box = json.loads(raw or b"null")
        lbl = ROOT / (f.stem + ".txt")
        if box:
            # YOLO: class cx cy w h, normalised. Clamped to the frame, because a
            # box guessed past the edge breaks the "page continues that way"
            # signal the guidance cues are built on.
            x = max(0.0, min(1.0, box["x"]))
            y = max(0.0, min(1.0, box["y"]))
            w = min(box["w"], 1.0 - x)
            h = min(box["h"], 1.0 - y)
            lbl.write_text(f"0 {x + w/2:.6f} {y + h/2:.6f} {w:.6f} {h:.6f}\n")
        else:
            lbl.write_text("")
        self._send(200, b'{"ok":true}')


def main():
    global ROOT
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()

    ROOT = Path(args.folder).resolve()
    n = len(list(ROOT.glob("*.jpg")))
    print(f"{n} frames in {ROOT}")
    print(f"open http://localhost:{args.port}/   (ctrl+c to stop)")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
