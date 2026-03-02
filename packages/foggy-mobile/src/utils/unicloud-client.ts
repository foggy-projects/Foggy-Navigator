/**
 * uniCloud 客户端 API 协议
 *
 * 绕过 HTTP trigger（需 URL化），直接通过 client API 调用云函数。
 * 签名算法: HmacMD5(sortedQueryString, clientSecret)
 */

const API_URL = 'https://api.next.bspapp.com/client'
const SPACE_ID = 'mp-4af7054d-5a40-4315-8678-df36b44298bb'
const CLIENT_SECRET = 'bFl25CiSXhK7K979vXI2rA=='
const APPID = '__UNI__AC2B8DB'

// ============================================================
// MD5 (RFC 1321, operates on byte arrays)
// ============================================================

const T: number[] = []
for (let i = 0; i < 64; i++) T[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000)

const S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]

function add32(a: number, b: number) { return (a + b) >>> 0 }
function rotl32(x: number, n: number) { return ((x << n) | (x >>> (32 - n))) >>> 0 }

function md5Bytes(input: number[]): number[] {
  // Pad input
  const msg = input.slice()
  const bitLen = msg.length * 8
  msg.push(0x80)
  while (msg.length % 64 !== 56) msg.push(0)
  // Append bit length as 64-bit LE (lower 32 bits, then upper 32 bits)
  msg.push(bitLen & 0xff, (bitLen >>> 8) & 0xff, (bitLen >>> 16) & 0xff, (bitLen >>> 24) & 0xff)
  msg.push(0, 0, 0, 0) // upper 32 bits (0 for messages < 512MB)

  // Parse into 32-bit LE words
  const M: number[] = []
  for (let i = 0; i < msg.length; i += 4) {
    M.push((msg[i] | (msg[i + 1] << 8) | (msg[i + 2] << 16) | (msg[i + 3] << 24)) >>> 0)
  }

  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476

  for (let block = 0; block < M.length; block += 16) {
    let a = a0, b = b0, c = c0, d = d0
    for (let i = 0; i < 64; i++) {
      let f: number, g: number
      if (i < 16) { f = (b & c) | ((~b >>> 0) & d); g = i }
      else if (i < 32) { f = (d & b) | ((~d >>> 0) & c); g = (5 * i + 1) % 16 }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16 }
      else { f = (c ^ (b | (~d >>> 0))) >>> 0; g = (7 * i) % 16 }
      f >>>= 0
      const tmp = d
      d = c
      c = b
      b = add32(b, rotl32(add32(add32(a, f), add32(T[i], M[block + g])), S[i]))
      a = tmp
    }
    a0 = add32(a0, a); b0 = add32(b0, b); c0 = add32(c0, c); d0 = add32(d0, d)
  }

  // Output as 16 bytes (LE)
  const out: number[] = []
  for (const v of [a0, b0, c0, d0]) {
    out.push(v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff)
  }
  return out
}

/** Convert UTF-8 string to bytes */
function utf8ToBytes(s: string): number[] {
  const bytes: number[] = []
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i)
    if (c < 0x80) bytes.push(c)
    else if (c < 0x800) { bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f)) }
    else if (c >= 0xd800 && c < 0xdc00 && i + 1 < s.length) {
      const c2 = s.charCodeAt(++i)
      c = ((c & 0x3ff) << 10 | (c2 & 0x3ff)) + 0x10000
      bytes.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 0x3f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f))
    } else {
      bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f))
    }
  }
  return bytes
}

function bytesToHex(bytes: number[]): string {
  return bytes.map(b => b.toString(16).padStart(2, '0')).join('')
}

function md5Hex(s: string): string {
  return bytesToHex(md5Bytes(utf8ToBytes(s)))
}

// ============================================================
// HMAC-MD5 (RFC 2104, operates on byte arrays)
// ============================================================

function hmacMD5(data: string, key: string): string {
  const dataBytes = utf8ToBytes(data)
  let keyBytes = utf8ToBytes(key)

  // If key > 64 bytes, hash it
  if (keyBytes.length > 64) keyBytes = md5Bytes(keyBytes)
  // Pad key to 64 bytes
  while (keyBytes.length < 64) keyBytes.push(0)

  const ipad = keyBytes.map(b => b ^ 0x36)
  const opad = keyBytes.map(b => b ^ 0x5c)

  // inner = MD5(ipad + data)
  const innerHash = md5Bytes(ipad.concat(dataBytes))
  // outer = MD5(opad + innerHash)
  return bytesToHex(md5Bytes(opad.concat(innerHash)))
}

// ============================================================
// uniCloud API
// ============================================================

function signBody(body: Record<string, any>): string {
  let str = ''
  Object.keys(body).sort().forEach(k => {
    if (body[k]) str += '&' + k + '=' + body[k]
  })
  return hmacMD5(str.slice(1), CLIENT_SECRET)
}

async function cloudRequest(body: Record<string, any>, accessToken?: string): Promise<any> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (accessToken) {
    body.token = accessToken
    headers['x-basement-token'] = accessToken
  }
  headers['x-serverless-sign'] = signBody(body)

  return new Promise((resolve, reject) => {
    uni.request({
      url: API_URL,
      method: 'POST',
      header: headers,
      data: body,
      success: (res) => resolve(res.data),
      fail: (err) => reject(new Error(err.errMsg)),
    })
  })
}

async function getAccessToken(): Promise<string> {
  const result = await cloudRequest({
    method: 'serverless.auth.user.anonymousAuthorize',
    params: '{}',
    spaceId: SPACE_ID,
    timestamp: Date.now(),
  })
  if (!result.success || !result.data?.accessToken) {
    throw new Error(`accessToken failed: ${JSON.stringify(result)}`)
  }
  return result.data.accessToken
}

/**
 * 通过 client API 协议调用 uni-upgrade-center 云函数
 */
export async function callUpgradeCenter(
  appVersion: string,
  wgtVersion: string,
): Promise<{ success: boolean; data?: any; error?: string }> {
  try {
    const accessToken = await getAccessToken()

    const result = await cloudRequest({
      method: 'serverless.function.runtime.invoke',
      params: JSON.stringify({
        functionTarget: 'uni-upgrade-center',
        functionArgs: {
          action: 'checkVersion',
          appid: APPID,
          appVersion,
          wgtVersion,
          platform: 'android',
        },
      }),
      spaceId: SPACE_ID,
      timestamp: Date.now(),
    }, accessToken)

    if (result.success && result.data) {
      return { success: true, data: result.data }
    }
    return { success: false, error: JSON.stringify(result.error || result) }
  } catch (e: any) {
    return { success: false, error: e.message || String(e) }
  }
}
