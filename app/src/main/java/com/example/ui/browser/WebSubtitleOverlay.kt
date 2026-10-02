package com.example.ui.browser

/** JavaScript bridge overlay for captions while the site's own video player remains active. */
object WebSubtitleOverlay {
    val script = """
        (function() {
          if (window.__omniSubtitleOverlayInstalled) return;
          window.__omniSubtitleOverlayInstalled = true;
          var enabled = true, seq = 0, lastText = '', autoHideTimer = null;
          var box = document.createElement('div');
          box.id = '__omni_translated_subtitle';
          box.style.cssText = 'position:fixed;z-index:2147483647;left:8%;right:8%;bottom:12%;display:none;opacity:0;transform:translateY(6px) scale(0.98);transition:opacity 0.22s cubic-bezier(0.16, 1, 0.3, 1), transform 0.22s cubic-bezier(0.16, 1, 0.3, 1);text-align:center;pointer-events:none;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Cairo",sans-serif;font-size:19px;font-weight:700;line-height:1.45;color:#F8FAFC;background:rgba(12, 16, 28, 0.88);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border:1px solid rgba(255, 255, 255, 0.16);box-shadow:0 8px 32px rgba(0,0,0,0.6), 0 2px 8px rgba(0,0,0,0.4);border-radius:12px;padding:10px 18px;text-shadow:0 2px 4px rgba(0,0,0,0.9);direction:auto;max-width:92%;margin:0 auto;box-sizing:border-box;';
          (document.body || document.documentElement).appendChild(box);
          var activeVideo = null;
          function place() {
            var v = activeVideo || document.querySelector('video');
            if (!v) return;
            var r = v.getBoundingClientRect();
            if (!r.width || !r.height) return;
            box.style.left = Math.max(8, r.left + r.width * .06) + 'px';
            box.style.width = Math.max(80, r.width * .88) + 'px';
            box.style.right = 'auto';
            box.style.bottom = 'auto';
            box.style.top = Math.max(8, r.bottom - Math.min(125, r.height * .24)) + 'px';
          }
          function show(text) {
            clearTimeout(autoHideTimer);
            if (text && text.trim()) {
              box.textContent = text.trim();
              box.style.display = 'block';
              requestAnimationFrame(function() {
                box.style.opacity = '1';
                box.style.transform = 'translateY(0) scale(1)';
              });
              // Auto-dismiss after 4 seconds of silence
              autoHideTimer = setTimeout(function() {
                box.style.opacity = '0';
                box.style.transform = 'translateY(6px) scale(0.98)';
                setTimeout(function() { if (box.style.opacity === '0') box.style.display = 'none'; }, 220);
              }, 4000);
            } else {
              box.style.opacity = '0';
              box.style.transform = 'translateY(6px) scale(0.98)';
              setTimeout(function() { if (box.style.opacity === '0') box.style.display = 'none'; }, 220);
            }
          }
          window.OmniSubtitleSetEnabled = function(v) { enabled = !!v; if (!enabled) show(''); };
          window.OmniSubtitleSetTranslated = function(id, text) {
            if (!enabled || id !== seq) return;
            show(text);
          };
          window.OmniSubtitleSetLive = function(text) {
            if (!enabled) return;
            show(text);
          };
          window.OmniApplyPageTranslation = function(raw) {
            try {
              var map = JSON.parse(raw || '{}');
              var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
              while (w.nextNode()) {
                var n=w.currentNode,p=n.parentElement;
                if(!p||/^(SCRIPT|STYLE|NOSCRIPT|INPUT|TEXTAREA|SELECT|BUTTON)$/.test(p.tagName)) continue;
                var original=(n.nodeValue||'').trim(), translated=map[original];
                if(translated && !p.hasAttribute('data-omni-original')) { p.setAttribute('data-omni-original',p.textContent||''); n.nodeValue=(n.nodeValue||'').replace(original,translated); }
              }
            } catch(e) {}
          };
          function send(text, start, end) {
            text = (text || '').replace(/<[^>]*>/g,'').trim();
            if (!enabled || !text || text === lastText) return;
            lastText = text; seq++;
            try { window.OmniBridge && window.OmniBridge.onSubtitleCue(text, start, end, seq); } catch(e) {}
          }
          function inspect() {
            var v = document.querySelector('video');
            if (!v) return;
            activeVideo = v;
            place();
            var tracks = v.textTracks || [];
            for (var i=0; i<tracks.length; i++) {
              try { tracks[i].mode = 'hidden'; } catch(e) {}
              tracks[i].oncuechange = function() {
                var active = this.activeCues && this.activeCues[0];
                if (active) send(active.text, Math.round(active.startTime*1000), Math.round(active.endTime*1000));
                else { lastText=''; show(''); }
              };
            }
            if (!v.__omniTimeHook) {
              v.__omniTimeHook = true;
              v.addEventListener('timeupdate', function() {
                for (var j=0; j<(v.textTracks||[]).length; j++) {
                  var a=v.textTracks[j].activeCues;
                  if (a && a.length) { var c=a[0]; send(c.text, Math.round(c.startTime*1000), Math.round(c.endTime*1000)); return; }
                }
              });
            }
          }
          document.addEventListener('fullscreenchange', function() {
            var host = document.fullscreenElement || document.body || document.documentElement;
            if (box.parentNode !== host) host.appendChild(box);
            inspect(); place();
          });
          window.addEventListener('resize', place);
          window.addEventListener('scroll', place, {passive:true});
          inspect(); place();
          new MutationObserver(inspect).observe(document.documentElement,{childList:true,subtree:true});
          setInterval(inspect, 1500);
        })();
    """.trimIndent()
}
