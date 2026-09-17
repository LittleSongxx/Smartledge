import { afterEach } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'

enableAutoUnmount(afterEach)

afterEach(() => {
  document.body.style.overflow = ''
})

if (!window.matchMedia) {
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent() { return false }
  })
}

/*
 * Node 25 起在全局暴露了实验性的 Web Storage，它会盖住 jsdom 的实现，
 * 而那个实现不完整（缺少 clear），使依赖 localStorage 的用例直接抛
 * "localStorage.clear is not a function"。这里显式装一个完整的 Storage，
 * 让测试环境不随 Node 版本漂移。
 */
if (typeof window.localStorage?.clear !== 'function') {
  const store = new Map()
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      get length() {
        return store.size
      },
      key: (index) => [...store.keys()][index] ?? null,
      getItem: (key) => (store.has(String(key)) ? store.get(String(key)) : null),
      setItem: (key, value) => {
        store.set(String(key), String(value))
      },
      removeItem: (key) => {
        store.delete(String(key))
      },
      clear: () => {
        store.clear()
      }
    }
  })
}
