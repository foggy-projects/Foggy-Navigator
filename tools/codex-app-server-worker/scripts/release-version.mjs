import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

const SEMVER_PATTERN = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch (error) {
    throw new Error(`Unable to read ${label}: ${error instanceof Error ? error.message : String(error)}`)
  }
}

export function readSourceAppVersion(filePath) {
  const source = ts.createSourceFile(
    filePath,
    fs.readFileSync(filePath, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )

  for (const statement of source.statements) {
    if (!ts.isVariableStatement(statement)) continue
    const isExported = statement.modifiers?.some(modifier => modifier.kind === ts.SyntaxKind.ExportKeyword)
    if (!isExported) continue
    for (const declaration of statement.declarationList.declarations) {
      if (!ts.isIdentifier(declaration.name) || declaration.name.text !== 'APP_VERSION') continue
      if (!declaration.initializer || !ts.isStringLiteral(declaration.initializer)) {
        throw new Error('Source APP_VERSION must be a string literal')
      }
      return declaration.initializer.text
    }
  }

  throw new Error('Source APP_VERSION export was not found')
}

export function resolveReleaseVersion(workerDir) {
  const packageJson = readJson(path.join(workerDir, 'package.json'), 'package.json')
  const packageLock = readJson(path.join(workerDir, 'package-lock.json'), 'package-lock.json')
  const version = packageJson.version
  if (typeof version !== 'string' || !SEMVER_PATTERN.test(version)) {
    throw new Error(`Invalid package version: ${String(version)}`)
  }

  const sourceVersion = readSourceAppVersion(path.join(workerDir, 'src', 'version.ts'))
  if (sourceVersion !== version) {
    throw new Error(`Source APP_VERSION ${sourceVersion} does not match package version ${version}`)
  }
  if (packageLock.version !== version || packageLock.packages?.['']?.version !== version) {
    throw new Error(`package-lock.json version does not match package version ${version}`)
  }

  return version
}
