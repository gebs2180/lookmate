// A small QR code encoder for the "Share your page" card, so the code is made here and no third-party service sees
// the stylist's link. Byte mode, error correction level M (15%), the smallest version that fits, the mask with the
// lowest penalty. Follows ISO/IEC 18004 the way Project Nayuki's reference encoder does.

/** The modules, row by row: true is dark. */
export type QrCode = { size: number; modules: boolean[][] }

// Level M, by version (index 0 unused).
const EC_CODEWORDS_PER_BLOCK = [
  -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
  28, 28, 28, 28, 28, 28, 28, 28,
]
const EC_BLOCKS = [
  -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40,
  43, 45, 47, 49,
]
/** Level M's two format bits. */
const EC_FORMAT_BITS = 0

function rawModules(version: number): number {
  let n = (16 * version + 128) * version + 64
  if (version >= 2) {
    const align = Math.floor(version / 7) + 2
    n -= (25 * align - 10) * align - 55
    if (version >= 7) n -= 36
  }
  return n
}

function dataCodewords(version: number): number {
  return Math.floor(rawModules(version) / 8) - EC_CODEWORDS_PER_BLOCK[version] * EC_BLOCKS[version]
}

/** Encodes text (as UTF-8) at level M. Throws when it can't fit in version 40. */
export function encodeQr(text: string): QrCode {
  const bytes = new TextEncoder().encode(text)
  let version = 1
  for (; ; version++) {
    if (version > 40) throw new Error('Too long for a QR code')
    const countBits = version <= 9 ? 8 : 16
    if (4 + countBits + bytes.length * 8 <= dataCodewords(version) * 8) break
  }
  const bits: number[] = []
  const push = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1)
  }
  push(0b0100, 4)
  push(bytes.length, version <= 9 ? 8 : 16)
  bytes.forEach((b) => push(b, 8))
  const capacity = dataCodewords(version) * 8
  push(0, Math.min(4, capacity - bits.length))
  push(0, (8 - (bits.length % 8)) % 8)
  for (let pad = 0xec; bits.length < capacity; pad ^= 0xec ^ 0x11) push(pad, 8)
  const data: number[] = []
  for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0))
  return draw(version, interleave(version, data))
}

// Reed-Solomon over GF(256), polynomial 0x11D.
function gfMultiply(x: number, y: number): number {
  let z = 0
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d)
    z ^= ((y >>> i) & 1) * x
  }
  return z
}

export function rsDivisor(degree: number): number[] {
  const result = new Array<number>(degree).fill(0)
  result[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = gfMultiply(result[j], root)
      if (j + 1 < result.length) result[j] ^= result[j + 1]
    }
    root = gfMultiply(root, 0x02)
  }
  return result
}

export function rsRemainder(data: number[], divisor: number[]): number[] {
  const result = new Array<number>(divisor.length).fill(0)
  for (const b of data) {
    const factor = b ^ (result.shift() as number)
    result.push(0)
    divisor.forEach((coef, i) => (result[i] ^= gfMultiply(coef, factor)))
  }
  return result
}

function interleave(version: number, data: number[]): number[] {
  const numBlocks = EC_BLOCKS[version]
  const ecLen = EC_CODEWORDS_PER_BLOCK[version]
  const raw = Math.floor(rawModules(version) / 8)
  const numShort = numBlocks - (raw % numBlocks)
  const shortLen = Math.floor(raw / numBlocks)
  const divisor = rsDivisor(ecLen)
  const blocks: number[][] = []
  for (let i = 0, k = 0; i < numBlocks; i++) {
    const dat = data.slice(k, k + shortLen - ecLen + (i < numShort ? 0 : 1))
    k += dat.length
    const ecc = rsRemainder(dat, divisor)
    if (i < numShort) dat.push(0)
    blocks.push(dat.concat(ecc))
  }
  const result: number[] = []
  for (let i = 0; i < blocks[0].length; i++) {
    blocks.forEach((block, j) => {
      if (i !== shortLen - ecLen || j >= numShort) result.push(block[i])
    })
  }
  return result
}

function alignmentPositions(version: number, size: number): number[] {
  if (version === 1) return []
  const count = Math.floor(version / 7) + 2
  const step = version === 32 ? 26 : Math.ceil((version * 4 + 4) / (count * 2 - 2)) * 2
  const result = [6]
  for (let pos = size - 7; result.length < count; pos -= step) result.splice(1, 0, pos)
  return result
}

function draw(version: number, codewords: number[]): QrCode {
  const size = version * 4 + 17
  const modules = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  const isFunction = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  const set = (x: number, y: number, dark: boolean) => {
    modules[y][x] = dark
    isFunction[y][x] = true
  }

  for (let i = 0; i < size; i++) {
    set(6, i, i % 2 === 0)
    set(i, 6, i % 2 === 0)
  }
  const finder = (cx: number, cy: number) => {
    for (let dy = -4; dy <= 4; dy++)
      for (let dx = -4; dx <= 4; dx++) {
        const d = Math.max(Math.abs(dx), Math.abs(dy))
        const x = cx + dx
        const y = cy + dy
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4)
      }
  }
  finder(3, 3)
  finder(size - 4, 3)
  finder(3, size - 4)
  const align = alignmentPositions(version, size)
  align.forEach((ay, i) =>
    align.forEach((ax, j) => {
      if ((i === 0 && j === 0) || (i === 0 && j === align.length - 1) || (i === align.length - 1 && j === 0)) return
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
    }),
  )
  drawFormat(0, set, size) // reserve the format areas; redrawn with the chosen mask below
  if (version >= 7) {
    let rem = version
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25)
    const bits = (version << 12) | rem
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) !== 0
      const a = size - 11 + (i % 3)
      const b = Math.floor(i / 3)
      set(a, b, dark)
      set(b, a, dark)
    }
  }

  // The data, in the zigzag from the bottom right.
  let i = 0
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j
        const upward = ((right + 1) & 2) === 0
        const y = upward ? size - 1 - vert : vert
        if (!isFunction[y][x] && i < codewords.length * 8) {
          modules[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) !== 0
          i++
        }
      }
    }
  }

  let best = 0
  let bestPenalty = Infinity
  for (let mask = 0; mask < 8; mask++) {
    applyMask(mask, modules, isFunction)
    drawFormat(mask, set, size)
    const p = penalty(modules)
    if (p < bestPenalty) {
      best = mask
      bestPenalty = p
    }
    applyMask(mask, modules, isFunction)
  }
  applyMask(best, modules, isFunction)
  drawFormat(best, set, size)
  return { size, modules }
}

/** The 15 format bits for level M and a mask: BCH(15,5), then XOR 0x5412. */
export function formatBits(mask: number): number {
  const data = (EC_FORMAT_BITS << 3) | mask
  let rem = data
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
  return ((data << 10) | rem) ^ 0x5412
}

function drawFormat(mask: number, set: (x: number, y: number, dark: boolean) => void, size: number) {
  const bits = formatBits(mask)
  const bit = (i: number) => ((bits >>> i) & 1) !== 0
  for (let i = 0; i <= 5; i++) set(8, i, bit(i))
  set(8, 7, bit(6))
  set(8, 8, bit(7))
  set(7, 8, bit(8))
  for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i))
  for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i))
  for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i))
  set(8, size - 8, true)
}

function applyMask(mask: number, modules: boolean[][], isFunction: boolean[][]) {
  const size = modules.length
  for (let y = 0; y < size; y++)
    for (let x = 0; x < size; x++) {
      if (isFunction[y][x]) continue
      let invert: boolean
      switch (mask) {
        case 0: invert = (x + y) % 2 === 0; break
        case 1: invert = y % 2 === 0; break
        case 2: invert = x % 3 === 0; break
        case 3: invert = (x + y) % 3 === 0; break
        case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break
        case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break
        case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break
        default: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
      }
      if (invert) modules[y][x] = !modules[y][x]
    }
}

/** The standard's penalty score (N1 to N4); the mask with the lowest wins. */
function penalty(modules: boolean[][]): number {
  const size = modules.length
  let result = 0
  const line = (get: (i: number) => boolean) => {
    let run = 1
    for (let i = 1; i <= size; i++) {
      if (i < size && get(i) === get(i - 1)) run++
      else {
        if (run >= 5) result += 3 + (run - 5)
        run = 1
      }
    }
    // Finder-like 1:1:3:1:1 with four light modules on either side.
    for (let i = 0; i + 10 < size; i++) {
      const p = [1, 0, 1, 1, 1, 0, 1].map((_, k) => get(i + 2 + k))
      const core = p[0] && !p[1] && p[2] && p[3] && p[4] && !p[5] && p[6]
      if (!core) continue
      const before = !get(i) && !get(i + 1)
      const after = i + 13 < size + 4 && [9, 10].every((k) => i + k >= size || !get(i + k))
      if (before || after) result += 40
    }
  }
  for (let y = 0; y < size; y++) line((x) => modules[y][x])
  for (let x = 0; x < size; x++) line((y) => modules[y][x])
  for (let y = 0; y < size - 1; y++)
    for (let x = 0; x < size - 1; x++) {
      const c = modules[y][x]
      if (c === modules[y][x + 1] && c === modules[y + 1][x] && c === modules[y + 1][x + 1]) result += 3
    }
  let dark = 0
  modules.forEach((row) => row.forEach((m) => m && dark++))
  const total = size * size
  result += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10
  return result
}

/** An SVG of the code with a four-module quiet zone, one path, scaled by the viewBox. */
export function qrSvgPath(code: QrCode): string {
  let d = ''
  code.modules.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) d += `M${x + 4} ${y + 4}h1v1h-1z`
    }),
  )
  return d
}
