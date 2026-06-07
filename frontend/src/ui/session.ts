/**
 * Tożsamość zapamiętana na czas życia KARTY przeglądarki oraz przełącznik diagnostyki.
 *
 * Wydzielone z App.tsx, bo są to jedyne miejsca sięgające do `sessionStorage` i do adresu
 * strony — czyli do stanu spoza aplikacji. Trzymanie ich razem sprawia, że da się na jeden
 * rzut oka powiedzieć, co ta aplikacja czyta z otoczenia.
 */
import type { Session } from '../api'

export const SESSION_KEY = 'inkline.session'

/** Tryb diagnostyczny — wyłącznie do pracy nad projektem i testów chaosu. */
export const DEBUG = new URLSearchParams(window.location.search).has('debug')

/**
 * Sesja przeżywa przeładowanie strony.
 *
 * `sessionStorage`, nie `localStorage`: tożsamość ma być związana z KARTĄ, nie z przeglądarką.
 * Przy `localStorage` dwie karty otwarte obok siebie dzieliłyby konto — czyli scenariusz
 * „dwie osoby w dwóch oknach" przestałby działać.
 */
export function loadSession(): Session | null {
  const stored = sessionStorage.getItem(SESSION_KEY)
  if (stored === null) return null
  try {
    return JSON.parse(stored) as Session
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}
