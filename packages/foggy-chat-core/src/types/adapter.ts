import type { AipMessage } from './aip'

export interface EventAdapter<TRaw = unknown> {
  convert(raw: TRaw, sessionId: string): AipMessage[]
}
