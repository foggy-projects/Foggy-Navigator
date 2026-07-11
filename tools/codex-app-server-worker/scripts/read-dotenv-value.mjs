import fs from 'node:fs'
import dotenv from 'dotenv'

const [, , envFile, key] = process.argv

if (!envFile || !key || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
  process.stderr.write('Usage: node scripts/read-dotenv-value.mjs <env-file> <key>\n')
  process.exit(2)
}

if (!fs.existsSync(envFile)) process.exit(0)

const values = dotenv.parse(fs.readFileSync(envFile))
if (Object.prototype.hasOwnProperty.call(values, key)) {
  process.stdout.write(values[key])
}
