import type { CSSProperties } from 'react'

/**
 * "Ink-rise": each word of a headline rises out of its own mask, 45ms after the one before (lookbook.css). The words
 * stay plain text, so the heading reads and copies normally; `start` continues the count across several pieces.
 */
export function InkRise({ text, start = 0 }: { text: string; start?: number }) {
  const words = text.split(' ')
  return (
    <>
      {words.map((word, i) => (
        <span key={i}>
          <span className="w">
            <span style={{ '--i': start + i } as CSSProperties}>{word}</span>
          </span>
          {i < words.length - 1 ? ' ' : ''}
        </span>
      ))}
    </>
  )
}
