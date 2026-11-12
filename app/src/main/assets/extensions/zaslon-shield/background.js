/* ============================================================================
 * Zaslon Shield & uBlock Filter Core — GeckoView Yerleşik WebExtension (Arka Plan)
 * - Ağ seviyesi Reklam, İzleyici ve Telemetri Engelleyici (EasyList + EasyPrivacy + TR)
 * - Genişletilmiş ClearURLs takip parametresi temizleyici
 * - Canlı İstek Denetçisi (Request Inspector) köprüsü
 * - YouTube SponsorBlock SHA-256 K-Anonimlik sorgulayıcı
 * ============================================================================ */

const BLOCKED_DOMAINS = new Set([
  // Küresel Reklam & İzleyici Ağları (EasyList / EasyPrivacy Çekirdek)
  'doubleclick.net', 'googlesyndication.com', 'googleadservices.com',
  'google-analytics.com', 'googletagmanager.com', 'googletagservices.com',
  'adservice.google.com', 'pagead2.googlesyndication.com', 'tpc.googlesyndication.com',
  'connect.facebook.net', 'an.facebook.com', 'pixel.facebook.com', 'graph.facebook.com',
  'ads-twitter.com', 'analytics.twitter.com', 'static.ads-twitter.com',
  'analytics.tiktok.com', 'ads-api.tiktok.com', 'business-api.tiktok.com',
  'adnxs.com', 'ib.adnxs.com', 'rubiconproject.com', 'pubmatic.com',
  'openx.net', 'criteo.com', 'criteo.net', 'taboola.com', 'outbrain.com',
  'mgid.com', 'revcontent.com', 'adroll.com', 'teads.tv', 'smartadserver.com',
  'casalemedia.com', 'indexww.com', '3lift.com', 'sharethrough.com',
  'moatads.com', 'doubleverify.com', 'adsafeprotected.com', 'scorecardresearch.com',
  'quantserve.com', 'hotjar.com', 'mouseflow.com', 'fullstory.com',
  'luckyorange.com', 'clarity.ms', 'mixpanel.com', 'amplitude.com',
  'segment.io', 'segment.com', 'heapanalytics.com', 'bugsnag.com',
  'sentry-cdn.com', 'appsflyer.com', 'adjust.com', 'branch.io', 'kochava.com',
  'chartbeat.com', 'newrelic.com', 'nr-data.net', 'omtrdc.net', '2o7.net',
  'demdex.net', 'everesttech.net', 'krxd.net', 'bluekai.com', 'exelator.com',
  'lotame.com', 'rlcdn.com', 'tapad.com', 'adsrvr.org', 'mathtag.com',
  'turn.com', 'bidswitch.net', 'contextweb.com', 'lijit.com', 'sovrn.com',
  'gumgum.com', 'yieldmo.com', 'kargo.com', 'popads.net', 'popcash.net',
  'propellerads.com', 'adsterra.com', 'exoclick.com', 'juicyads.com',
  'trafficjunky.net', 'yandex.ru', 'mc.yandex.ru', 'an.yandex.ru',
  // Türkiye Yerel Reklam Ağları
  'reklamstore.com', 'admatic.com.tr', 'virgul.com', 'medyanet.com.tr',
  'admost.com', 'adhood.com', 'wordego.com', 'enzokullanan.com',
  // Telemetri & Arka Plan Ping Sunucuları (Sıfır-Güven Güvenlik Duvarı)
  'telemetry.mozilla.org', 'incoming.telemetry.mozilla.org',
  'shavar.services.mozilla.com', 'location.services.mozilla.com',
  'push.services.mozilla.com', 'normandy.cdn.mozilla.net',
  'clients2.google.com', 'update.googleapis.com', 'safebrowsing.googleapis.com',
  'content-autofill.googleapis.com', 'connectivitycheck.gstatic.com'
]);

const TRACKING_PARAMS = new Set([
  'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content', 'utm_id',
  'fbclid', 'gclid', 'gclsrc', 'dclid', 'msclkid', 'twclid', 'ttclid', 'yclid',
  'mc_cid', 'mc_eid', 'igshid', 'si', '_hsenc', '_hsmi', 'mkt_tok', 'spm',
  'scm', 'rb_clickid', 'oly_enc_id', 'oly_anon_id', 'vero_id', 'wickedid'
]);

const customBlockedDomains = new Set();
const whitelistedDomains = new Set();
const torTabIds = new Set();
const nonTorTabIds = new Set();
const torDocumentOrigins = new Set();
const torUrlHints = new Set();
const torServiceRequestUrls = new Set();
const nonTorServiceRequestUrls = new Set();
let shieldEnabled = true;
let sponsorBlockEnabled = true;
let backgroundAudioEnabled = true;
let videoSpeedRate = 1.0;
let torActive = false;
let torVerified = false;
let torPort = 9050;
let circuitEpoch = 1;
let activeContainerId = 'personal';
let byeDpiActive = false;
let byeDpiPort = 10808;

// Native Android (HardenedGeckoEngine.kt) ile çift yönlü mesajlaşma portu
let nativePort = null;
function applyNativeConfig(msg) {
  if (!msg || typeof msg !== 'object') return;
  if (typeof msg.shieldEnabled === 'boolean') shieldEnabled = msg.shieldEnabled;
  if (typeof msg.sponsorBlockEnabled === 'boolean') sponsorBlockEnabled = msg.sponsorBlockEnabled;
  if (typeof msg.backgroundAudioEnabled === 'boolean') backgroundAudioEnabled = msg.backgroundAudioEnabled;
  if (typeof msg.videoSpeed === 'number') {
    const nextRate = Math.max(0.25, Math.min(4.0, msg.videoSpeed));
    if (nextRate !== videoSpeedRate) {
      videoSpeedRate = nextRate;
      if (typeof browser !== 'undefined' && browser.tabs && browser.tabs.query) {
        browser.tabs.query({}).then(tabs => {
          tabs.forEach(t => {
            if (typeof t.id === 'number') {
              browser.tabs.sendMessage(t.id, { type: 'SET_VIDEO_SPEED', videoSpeedRate }).catch(() => {});
            }
          });
        }).catch(() => {});
      }
    } else {
      videoSpeedRate = nextRate;
    }
  }
  if (typeof msg.torActive === 'boolean') torActive = msg.torActive;
  if (typeof msg.torVerified === 'boolean') torVerified = msg.torVerified;
  if (typeof msg.torPort === 'number' && msg.torPort > 0) torPort = msg.torPort;
  if (typeof msg.circuitEpoch === 'number' && msg.circuitEpoch > 0) circuitEpoch = msg.circuitEpoch;
  if (typeof msg.activeContainerId === 'string' && msg.activeContainerId) {
    activeContainerId = msg.activeContainerId;
  }
  if (typeof msg.byeDpiActive === 'boolean') byeDpiActive = msg.byeDpiActive;
  if (typeof msg.byeDpiPort === 'number' && msg.byeDpiPort > 0) byeDpiPort = msg.byeDpiPort;
  if (Array.isArray(msg.torUrlHints)) {
    torUrlHints.clear();
    msg.torUrlHints.forEach(u => {
      if (u) torUrlHints.add(String(u));
    });
  }
  if (msg.torSessionEnded === true || (!torActive && !torVerified && msg.clearTorTabs === true)) {
    torTabIds.clear();
    torDocumentOrigins.clear();
  }
  if (Array.isArray(msg.customBlocked)) {
    customBlockedDomains.clear();
    msg.customBlocked.forEach(d => customBlockedDomains.add(String(d).toLowerCase()));
  }
  if (Array.isArray(msg.whitelist)) {
    whitelistedDomains.clear();
    msg.whitelist.forEach(d => whitelistedDomains.add(String(d).toLowerCase()));
  }
}

function connectNativeBridge() {
  try {
    nativePort = browser.runtime.connectNative('zaslon_shield');
    nativePort.onMessage.addListener(msg => {
      if (!msg || typeof msg !== 'object') return;
      if (msg.type === 'SET_CONFIG') {
        applyNativeConfig(msg);
      }
    });
    nativePort.onDisconnect.addListener(() => {
      nativePort = null;
      setTimeout(connectNativeBridge, 3000);
    });
  } catch (e) {}
}
connectNativeBridge();

if (typeof browser !== 'undefined' && browser.tabs && browser.tabs.onRemoved) {
  browser.tabs.onRemoved.addListener(tabId => {
    torTabIds.delete(tabId);
    nonTorTabIds.delete(tabId);
  });
}

function notifyNative(payload) {
  try {
    if (nativePort) {
      nativePort.postMessage(payload);
    } else {
      browser.runtime.sendNativeMessage('zaslon_shield', payload).catch(() => {});
    }
  } catch (e) {}
}

function extractHostname(urlStr) {
  try {
    return new URL(urlStr).hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
}

function isDomainBlocked(host) {
  if (!host) return false;
  if (customBlockedDomains.has(host)) return true;
  if (BLOCKED_DOMAINS.has(host)) return true;
  const parts = host.split('.');
  for (let i = 1; i < parts.length - 1; i++) {
    const parent = parts.slice(i).join('.');
    if (customBlockedDomains.has(parent) || BLOCKED_DOMAINS.has(parent)) {
      return true;
    }
  }
  return false;
}

function classifyCategory(host, urlStr) {
  if (/telemetry|safebrowsing|clients2|gstatic|normandy|shavar/i.test(host)) return 'telemetry';
  if (/analytics|metric|hotjar|clarity|mixpanel|segment|scorecard|omtrdc|yandex/i.test(host)) return 'tracker';
  if (/ad|doubleclick|syndication|taboola|outbrain|criteo|pubmatic|rubicon|pop/i.test(host) || /\/ads\/|pagead|adserver/i.test(urlStr)) return 'ad';
  return 'request';
}

function isLoopbackHost(host) {
  return host === '127.0.0.1' || host === 'localhost' || host === '::1' || host === '[::1]';
}

function isOnionHost(host) {
  return Boolean(host && (host === 'onion' || host.endsWith('.onion')));
}

function getUrlOrigin(urlStr) {
  try {
    return new URL(urlStr).origin;
  } catch {
    return '';
  }
}

function recordTorOrigin(urlStr) {
  const origin = getUrlOrigin(urlStr);
  if (origin && origin !== 'null') {
    torDocumentOrigins.add(origin);
    if (torDocumentOrigins.size > 512) {
      const first = torDocumentOrigins.values().next().value;
      if (first) torDocumentOrigins.delete(first);
    }
  }
}

function extractRootDomain(host) {
  if (!host) return 'default';
  const clean = String(host).toLowerCase().replace(/^\[|\]$/g, '');
  if (/^\d+\.\d+\.\d+\.\d+$/.test(clean) || clean.includes(':')) return clean;
  const parts = clean.split('.').filter(Boolean);
  if (parts.length <= 2) return clean || 'default';
  const lastTwo = parts.slice(-2).join('.');
  const multiTlds = new Set(['co.uk', 'org.uk', 'ac.uk', 'com.tr', 'org.tr', 'net.tr', 'gov.tr', 'edu.tr', 'com.br', 'com.au', 'co.jp']);
  if (multiTlds.has(lastTwo) && parts.length >= 3) {
    return parts.slice(-3).join('.');
  }
  return lastTwo;
}

function isTorRequest(details) {
  const tabId = typeof details?.tabId === 'number' ? details.tabId : -1;
  const reqUrl = details?.url || '';
  const docUrl = details?.originUrl || details?.documentUrl || '';
  const docOrigin = getUrlOrigin(docUrl);

  if (tabId >= 0) {
    if (torTabIds.has(tabId)) {
      if (details?.type === 'main_frame') recordTorOrigin(reqUrl);
      return true;
    }
    if (reqUrl && torUrlHints.has(reqUrl)) {
      nonTorTabIds.delete(tabId);
      torTabIds.add(tabId);
      recordTorOrigin(reqUrl);
      return true;
    }
    if (nonTorTabIds.has(tabId)) {
      return false;
    }
    if (details?.type === 'main_frame') {
      if (activeContainerId === 'tor') {
        torTabIds.add(tabId);
        recordTorOrigin(reqUrl);
        return true;
      }
      nonTorTabIds.add(tabId);
      return false;
    }
    if (docOrigin && torDocumentOrigins.has(docOrigin)) {
      torTabIds.add(tabId);
      return true;
    }
    return activeContainerId === 'tor';
  }

  // tabId < 0 (arka plan eklenti istekleri: ör. SponsorBlock fetch)
  if (reqUrl && nonTorServiceRequestUrls.has(reqUrl)) return false;
  if (reqUrl && torServiceRequestUrls.has(reqUrl)) return true;
  if (docOrigin && torDocumentOrigins.has(docOrigin)) return true;
  return activeContainerId === 'tor';
}

function resolveProxyForRequest(details) {
  const reqUrl = details?.url || '';
  const reqHost = extractHostname(reqUrl);
  const topHost = extractHostname(details?.documentUrl || details?.originUrl || reqUrl) || reqHost || 'default';
  const fpRoot = extractRootDomain(topHost);
  const torContext = isTorRequest(details);

  if (!torContext && isOnionHost(reqHost)) {
    // Non-Tor sekmede .onion asla direct veya ByeDPI'dan çıkmasın (fail-closed kara delik)
    return {
      type: 'socks',
      host: '127.0.0.1',
      port: 9,
      proxyDNS: true
    };
  }

  if (torContext) {
    if (torVerified && torPort > 0) {
      return {
        type: 'socks',
        host: '127.0.0.1',
        port: torPort,
        proxyDNS: true,
        username: `zaslon-fp-${fpRoot}-e${circuitEpoch}`,
        password: '1'
      };
    }
    // Fail-closed: Tor SOCKS5 doğrulanmadıysa siyah delik portuna yönlendir, asla direct çıkma
    return {
      type: 'socks',
      host: '127.0.0.1',
      port: 9,
      proxyDNS: true
    };
  }

  if (isLoopbackHost(reqHost)) {
    return { type: 'direct' };
  }

  if (byeDpiActive && byeDpiPort > 0) {
    return {
      type: 'socks',
      host: '127.0.0.1',
      port: byeDpiPort,
      proxyDNS: true
    };
  }

  return { type: 'direct' };
}

if (typeof browser !== 'undefined' && browser.proxy && browser.proxy.onRequest) {
  browser.proxy.onRequest.addListener(
    details => resolveProxyForRequest(details),
    { urls: ['<all_urls>'] }
  );
}

// 1. Ağ Seviyesi Reklam / İzleyici Engelleme, Tor Fail-Closed & ClearURLs Temizleme
browser.webRequest.onBeforeRequest.addListener(
  details => {
    const reqUrl = details.url || '';
    if (!reqUrl.startsWith('http://') && !reqUrl.startsWith('https://')) {
      return {};
    }

    const reqHost = extractHostname(reqUrl);
    const topHost = extractHostname(details.originUrl || details.documentUrl || reqUrl);
    const torContext = isTorRequest(details);

    // Tor fail-closed: Tor portu doğrulanmadıysa hiçbir isteğin gerçek IP'den çıkmasına izin verme
    if (torContext && !torVerified) {
      notifyNative({
        type: 'TOR_BLOCKED',
        domain: reqHost,
        url: reqUrl.slice(0, 180),
        timestamp: Date.now()
      });
      return { cancel: true };
    }

    // Normal konteynerde .onion DNS/istek sızıntısını kesinlikle engelle
    if (isOnionHost(reqHost) && !torContext) {
      return { cancel: true };
    }

    if (!shieldEnabled || (topHost && whitelistedDomains.has(topHost))) {
      return {};
    }

    // Tracking ping / beacon / CSP raporlarını 3. partilere gönderme
    if ((details.type === 'ping' || details.type === 'beacon' || details.type === 'csp_report') && reqHost && topHost && reqHost !== topHost) {
      return { cancel: true };
    }

    // Reklam veya izleyici alan adı kontrolü
    if (isDomainBlocked(reqHost) || /\/pagead\/|\/ads\/|\/adserver|doubleclick\.net|googlesyndication\.com/i.test(reqUrl)) {
      const cat = classifyCategory(reqHost, reqUrl);
      notifyNative({
        type: 'REQUEST_LOG',
        domain: reqHost,
        url: reqUrl.slice(0, 180),
        category: cat === 'request' ? 'ad' : cat,
        blocked: true,
        timestamp: Date.now()
      });
      return { cancel: true };
    }

    // ClearURLs takip parametresi temizleme (Ana gezinme ve alt çerçevelerde)
    if (details.type === 'main_frame' || details.type === 'sub_frame') {
      try {
        const parsed = new URL(reqUrl);
        let cleanedCount = 0;
        for (const key of [...parsed.searchParams.keys()]) {
          if (TRACKING_PARAMS.has(key.toLowerCase()) || key.toLowerCase().startsWith('utm_')) {
            parsed.searchParams.delete(key);
            cleanedCount++;
          }
        }
        if (cleanedCount > 0) {
          notifyNative({
            type: 'URL_CLEANED',
            domain: reqHost,
            count: cleanedCount
          });
          return { redirectUrl: parsed.toString() };
        }
      } catch {}
    }

    // İzin verilen 3. parti istekleri Canlı İstek Denetçisine bildir
    if (reqHost && topHost && reqHost !== topHost) {
      notifyNative({
        type: 'REQUEST_LOG',
        domain: reqHost,
        url: reqUrl.slice(0, 180),
        category: classifyCategory(reqHost, reqUrl),
        blocked: false,
        timestamp: Date.now()
      });
    }

    return {};
  },
  { urls: ['<all_urls>'] },
  ['blocking']
);

// 2. YouTube SponsorBlock SHA-256 K-Anonimlik Yardımcıları
async function sha256HexPrefix(str, len = 4) {
  const data = new TextEncoder().encode(str);
  const hashBuf = await crypto.subtle.digest('SHA-256', data);
  const hashArr = Array.from(new Uint8Array(hashBuf));
  const hex = hashArr.map(b => b.toString(16).padStart(2, '0')).join('');
  return hex.slice(0, len);
}

const sponsorCache = new Map();

async function fetchSponsorSegments(videoId, senderTabId = -1) {
  if (!sponsorBlockEnabled || !videoId) return [];
  const inTor =
    (senderTabId >= 0 && torTabIds.has(senderTabId)) ||
    (!(senderTabId >= 0 && nonTorTabIds.has(senderTabId)) && activeContainerId === 'tor');
  if (inTor && !torVerified) return [];
  const cacheKey = `${inTor ? 'tor' : 'std'}:${videoId}`;
  if (sponsorCache.has(cacheKey)) return sponsorCache.get(cacheKey);

  let reqUrl = '';
  try {
    const prefix = await sha256HexPrefix(videoId, 4);
    const categories = encodeURIComponent(JSON.stringify(['sponsor', 'selfpromo', 'interaction', 'intro', 'outro']));
    reqUrl = `https://sponsor.ajay.app/api/skipSegments/${prefix}?categories=${categories}`;
    if (inTor) torServiceRequestUrls.add(reqUrl);
    else nonTorServiceRequestUrls.add(reqUrl);

    const res = await fetch(reqUrl, {
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      cache: 'no-store'
    });
    if (!res.ok) return [];
    const list = await res.json();
    const match = Array.isArray(list) ? list.find(item => item.videoID === videoId) : null;
    const segments = match && Array.isArray(match.segments)
      ? match.segments.map(s => ({
          start: s.segment[0],
          end: s.segment[1],
          category: s.category || 'sponsor',
          UUID: s.UUID
        }))
      : [];
    sponsorCache.set(cacheKey, segments);
    return segments;
  } catch {
    return [];
  } finally {
    if (reqUrl) {
      torServiceRequestUrls.delete(reqUrl);
      nonTorServiceRequestUrls.delete(reqUrl);
    }
  }
}

// 3. Content Script ile Mesajlaşma (SponsorBlock, Video Hız & Arka Plan Oynatma)
browser.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || typeof msg !== 'object') return false;

  if (msg.type === 'GET_MEDIA_CONFIG') {
    sendResponse({
      sponsorBlockEnabled,
      backgroundAudioEnabled,
      videoSpeedRate
    });
    return false;
  }

  if (msg.type === 'GET_SPONSOR_SEGMENTS') {
    const tabId = sender?.tab?.id ?? -1;
    fetchSponsorSegments(msg.videoId, tabId).then(segments => {
      sendResponse({
        segments,
        sponsorBlockEnabled,
        backgroundAudioEnabled,
        videoSpeedRate
      });
    });
    return true;
  }

  if (msg.type === 'SPONSOR_SKIPPED') {
    notifyNative({
      type: 'SPONSOR_SKIPPED',
      videoId: msg.videoId,
      category: msg.category,
      savedSeconds: msg.savedSeconds
    });
    return false;
  }

  if (msg.type === 'MEDIA_STATE_CHANGED') {
    notifyNative({
      type: 'MEDIA_STATE_CHANGED',
      playing: msg.playing,
      title: msg.title || '',
      url: msg.url || ''
    });
    return false;
  }

  return false;
});
