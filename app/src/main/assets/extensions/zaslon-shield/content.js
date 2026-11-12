/* ============================================================================
 * Zaslon Shield — Sayfa İçi Koruma & Medya Betiği (content.js)
 * 1. Ekran Kapalıyken YouTube / Video Arka Plan Oynatma Kilidi Açıcı
 * 2. SponsorBlock Otomatik Sponsor/Intro Atlama Motoru
 * 3. 0.25x – 4.0x Video Hız Kontrolü ve Zorunlu PiP
 * 4. Deterministik Parmak İzi Kalkanı (Canvas, Audio, Donanım Gürültüsü)
 * 5. Çerez (GDPR) Pop-up ve Reklam Boşluğu (Cosmetic Filtering) Bastırıcı
 * ============================================================================ */

(function () {
  'use strict';

  const host = (location.hostname || '').toLowerCase();

  // --------------------------------------------------------------------------
  // 1. EKRAN KAPALIYKEN ARKA PLAN OYNATMA (YouTube, YouTube Music, Twitch, Vimeo)
  // --------------------------------------------------------------------------
  const isMediaSite =
    host.includes('youtube.com') ||
    host.includes('youtu.be') ||
    host.includes('vimeo.com') ||
    host.includes('twitch.tv') ||
    host.includes('dailymotion.com') ||
    host.includes('soundcloud.com');

  if (isMediaSite) {
    try {
      Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
      Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
      Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });

      window.addEventListener(
        'visibilitychange',
        e => {
          e.stopImmediatePropagation();
        },
        true
      );
      window.addEventListener(
        'webkitvisibilitychange',
        e => {
          e.stopImmediatePropagation();
        },
        true
      );
      document.addEventListener(
        'visibilitychange',
        e => {
          e.stopImmediatePropagation();
        },
        true
      );

      // YouTube mobilin arka planda aktivite kontrolüyle videoyu duraklatmasını engelle
      setInterval(() => {
        try {
          window._lact = Date.now();
        } catch {}
      }, 30000);
    } catch {}
  }

  // --------------------------------------------------------------------------
  // 2. PARMAK İZİ KORUMASI
  // GeckoView yerel `privacy.resistFingerprinting: true` & `dom.battery.enabled: false`
  // ile çakışmaması için hardwareConcurrency, deviceMemory ve getImageData override edilmez.
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // 3. COSMETIC FILTERING & ÇEREZ POP-UP ENGELLEYİCİ
  // --------------------------------------------------------------------------
  const shieldCss = `
    .ad-showing .video-ads, .ytp-ad-module, .ytp-ad-overlay-container,
    ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer,
    div[id^="google_ads_"], ins.adsbygoogle, [id*="taboola"], [id*="outbrain"],
    #onetrust-consent-sdk, .fc-consent-root, #CybotCookiebotDialog, .cc-window {
      display: none !important;
      visibility: hidden !important;
      height: 0 !important;
      pointer-events: none !important;
    }
  `;

  let shieldCssInjected = false;
  const hookedVideos = new WeakSet();

  function injectShieldStyles() {
    if (shieldCssInjected) return;
    const target = document.head || document.documentElement;
    if (!target) return;
    shieldCssInjected = true;
    const style = document.createElement('style');
    style.textContent = shieldCss;
    target.appendChild(style);
  }

  if (document.documentElement) {
    injectShieldStyles();
  } else {
    document.addEventListener('DOMContentLoaded', injectShieldStyles, { once: true });
  }

  // --------------------------------------------------------------------------
  // 4. YOUTUBE SPONSORBLOCK + VİDEO HIZ (0.25x-4x) + ZORUNLU PiP
  // --------------------------------------------------------------------------
  let currentVideoId = null;
  let activeSegments = [];
  let lastSkippedUuid = null;
  let desiredRate = 1.0;

  function getYouTubeVideoId() {
    if (!host.includes('youtube.com')) return null;
    try {
      const u = new URL(location.href);
      if (u.searchParams.get('v')) return u.searchParams.get('v');
      const m = u.pathname.match(/\/shorts\/([a-zA-Z0-9_-]{6,})/);
      return m ? m[1] : null;
    } catch {
      return null;
    }
  }

  function requestSegmentsForCurrentVideo() {
    const vid = getYouTubeVideoId();
    if (!vid || vid === currentVideoId) return;
    currentVideoId = vid;
    activeSegments = [];

    browser.runtime
      .sendMessage({ type: 'GET_SPONSOR_SEGMENTS', videoId: vid })
      .then(res => {
        if (res && Array.isArray(res.segments)) {
          activeSegments = res.segments;
        }
        if (res && typeof res.videoSpeedRate === 'number' && res.videoSpeedRate > 0) {
          desiredRate = res.videoSpeedRate;
        }
      })
      .catch(() => {});
  }

  function enhanceVideos() {
    if (host.includes('youtube.com')) {
      requestSegmentsForCurrentVideo();
    }

    const videos = document.querySelectorAll('video');
    for (const v of videos) {
      if (v.hasAttribute('disablepictureinpicture')) {
        v.removeAttribute('disablepictureinpicture');
      }
      if (desiredRate !== 1.0 && Math.abs(v.playbackRate - desiredRate) > 0.01) {
        try { v.playbackRate = desiredRate; } catch {}
      }

      if (!hookedVideos.has(v)) {
        hookedVideos.add(v);

        v.addEventListener('play', () => {
          browser.runtime
            .sendMessage({
              type: 'MEDIA_STATE_CHANGED',
              playing: true,
              title: document.title,
              url: location.href
            })
            .catch(() => {});
        });

        v.addEventListener('timeupdate', () => {
          // YouTube video içi reklam oynatılıyorsa anında sonuna sar
          if (host.includes('youtube.com')) {
            const adPlayer = document.querySelector('.ad-showing');
            if (adPlayer && Number.isFinite(v.duration) && v.duration > 0) {
              v.currentTime = v.duration;
              const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
              if (skipBtn) skipBtn.click();
              return;
            }
          }

          // SponsorBlock segment kontrolü
          if (activeSegments.length > 0) {
            const t = v.currentTime;
            for (const seg of activeSegments) {
              if (t >= seg.start && t < seg.end - 0.25 && lastSkippedUuid !== seg.UUID) {
                lastSkippedUuid = seg.UUID;
                const saved = Math.max(0, Math.round(seg.end - t));
                v.currentTime = seg.end;
                browser.runtime
                  .sendMessage({
                    type: 'SPONSOR_SKIPPED',
                    videoId: currentVideoId,
                    category: seg.category,
                    savedSeconds: saved
                  })
                  .catch(() => {});
                break;
              }
            }
          }
        });
      }
    }
  }

  function applyPlaybackRateToDom(rate) {
    const r = Math.max(0.25, Math.min(4.0, Number(rate) || 1.0));
    desiredRate = r;
    document.querySelectorAll('video').forEach(v => {
      try { v.playbackRate = r; } catch {}
    });
    return r;
  }

  if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.onMessage) {
    browser.runtime.onMessage.addListener(msg => {
      if (msg && msg.type === 'SET_VIDEO_SPEED' && typeof msg.videoSpeedRate === 'number') {
        applyPlaybackRateToDom(msg.videoSpeedRate);
      }
    });
    browser.runtime
      .sendMessage({ type: 'GET_MEDIA_CONFIG' })
      .then(res => {
        if (res && typeof res.videoSpeedRate === 'number' && res.videoSpeedRate > 0) {
          applyPlaybackRateToDom(res.videoSpeedRate);
        }
      })
      .catch(() => {});
  }

  setInterval(enhanceVideos, 1200);
})();
