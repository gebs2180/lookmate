/** The client pages (/find, /m/:token): no sign-in, outside the signed-in shell (CLAUDE.md). */
export function isPublicPath(pathname: string) {
  return pathname === '/find' || pathname.startsWith('/m/')
}
