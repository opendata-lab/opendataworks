import { permissionsApi } from '@/api/permissions'

/** Sources where the account currently has a membership or a bound database account. */
export const sourcesWithAccess = (access) =>
  (access?.sources || []).filter((source) => source.member)

/**
 * Removes the account from every source it belongs to, one source at a time. Every source is
 * attempted; the returned list names the ones that failed so callers can stop or offer a retry.
 */
export async function revokeAllAccess(access) {
  const failures = []
  for (const source of sourcesWithAccess(access)) {
    try {
      await permissionsApi.removeMember(source.sourceId, access.accountType, String(access.userId))
    } catch (error) {
      failures.push({
        sourceId: source.sourceId,
        clusterName: source.clusterName,
        message: error?.message || '移除失败',
      })
    }
  }
  return failures
}
