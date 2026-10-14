// Only public app assets are cached. Auth and document API responses are excluded.
const SHELL = 'collabedit-public-shell-v1'
self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL)
    const response = await fetch('/')
    if (!response.ok) throw new Error('App shell unavailable')
    const html = await response.clone().text()
    await cache.put('/', response)
    const assets = [...html.matchAll(/(?:src|href)=["']([^"']+)["']/g)]
      .map(match => new URL(match[1], self.location.origin))
      .filter(url => url.origin === self.location.origin && url.pathname.startsWith('/assets/'))
    await cache.addAll([...new Set(assets.map(url => url.href))])
    await self.skipWaiting()
  })())
})
self.addEventListener('message', event => {
  if (event.data?.type !== 'CACHE_PUBLIC_ASSETS' || !Array.isArray(event.data.urls)) return
  const urls = event.data.urls.filter(value => {
    if (typeof value !== 'string') return false
    const url = new URL(value, self.location.origin)
    return url.origin === self.location.origin && url.pathname.startsWith('/assets/')
  })
  event.waitUntil(caches.open(SHELL).then(cache => cache.addAll(urls)))
})
self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim())
})
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin || event.request.method !== 'GET') return
  const navigation = event.request.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')
  const asset = url.pathname.startsWith('/assets/') || url.pathname === '/favicon.svg'
  if (!navigation && !asset) return
  event.respondWith((async () => {
    const cache = await caches.open(SHELL)
    const key = navigation ? '/' : event.request
    try {
      const response = await fetch(event.request)
      if (response.ok && response.type !== 'opaque') {
        await cache.put(key, response.clone())
        const entries = await cache.keys()
        if (entries.length > 64) {
          const oldAssets = entries.filter(entry => new URL(entry.url).pathname.startsWith('/assets/'))
          await Promise.all(oldAssets.slice(0, entries.length - 64).map(entry => cache.delete(entry)))
        }
      }
      return response
    } catch (error) {
      const cached = await cache.match(key, { ignoreVary: true })
      if (cached) return cached
      throw error
    }
  })())
})
