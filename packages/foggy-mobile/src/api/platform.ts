import client from './client'
import type { RX, SetupStatus } from './types'

export async function getSetupStatus(): Promise<SetupStatus> {
  const rx = (await client.get('/config/platform/setup-status')) as unknown as RX<SetupStatus>
  return rx.data
}
