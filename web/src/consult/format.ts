/** 0.25 → "About 15 minutes", 2.5 → "About 2½ hours". */
export function formatHours(hours: number) {
  if (hours < 1) return `About ${Math.round(hours * 60)} minutes`
  const whole = Math.floor(hours)
  const rest = hours - whole
  const text = rest === 0 ? `${whole}` : rest === 0.5 ? `${whole}½` : `${Math.round(hours * 10) / 10}`
  return `About ${text} ${hours === 1 ? 'hour' : 'hours'}`
}
