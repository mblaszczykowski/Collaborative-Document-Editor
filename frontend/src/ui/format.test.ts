import { describe, expect, it } from 'vitest'
import { characterCount, wordCount } from './format'

describe('word count', () => {
  it('counts words rather than estimating from character length', () => {
    expect(wordCount('Hello team. This is a review draft.\nA second paragraph.')).toBe('10 words')
    expect(wordCount('antidisestablishmentarianism')).toBe('1 word')
    expect(wordCount('  \n😀')).toBe('0 words')
  })
  it('counts user-perceived characters including combining marks and emoji', () => {
    expect(characterCount('a😀e\u0301')).toBe(3)
    expect(characterCount('👨‍👩‍👧‍👦')).toBe(1)
  })
})
