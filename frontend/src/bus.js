// src/bus.js
// Single, importable event bus that replaces the ad-hoc
// `window.dispatchEvent(new CustomEvent(...))` / `window.addEventListener(...)`
// calls scattered across the app. Centralising cross-component messaging here
// keeps it typed, discoverable, and testable, and stops polluting the global
// `window` namespace.
//
// Migration (per occurrence):
//   BEFORE: window.dispatchEvent(new CustomEvent("gis-data-ready", { detail }))
//   AFTER:  import { emit } from "./bus"; emit("gis-data-ready", detail)
//
//   BEFORE: window.addEventListener("gis-data-ready", handler)
//   AFTER:  import { on } from "./bus"; on("gis-data-ready", handler)
//           // `on` returns an off() disposer — call it in onUnmounted().
const target = new EventTarget()

export function emit(name, detail) {
  target.dispatchEvent(new CustomEvent(name, { detail }))
}

export function on(name, handler) {
  target.addEventListener(name, handler)
  return () => target.removeEventListener(name, handler)
}

export function off(name, handler) {
  target.removeEventListener(name, handler)
}

export default { emit, on, off }
